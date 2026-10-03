package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 隐藏界面元素：一个登记表，一个压制出口。
 *
 * ## 谁往登记表里写
 *
 * | 来源 | 标记 | 受哪个开关控制 |
 * |---|---|---|
 * | [markTip]，由 RestrictionGuard 调用（发送状态图标等） | [FLAG_TIP_ICON] | 「别提示我被限制了」 |
 * | `setText` 命中**内置**限制词 | [FLAG_TIP_TEXT] | 「别提示我被限制了」 |
 * | `setText` 命中**用户**关键词 | [FLAG_KEYWORD] | 「关键词兜底」 |
 *
 * 用位标志放在一张表里，热路径只查一次；压制时按标记检查对应开关，
 * 所以关掉某个开关之后，它登记过的控件会立刻恢复正常。
 *
 * ## 为什么必须有「压制」这一层
 *
 * 两个独立的现实：
 *
 *  - **调用方会在 setText / 显示方法之后再把控件显示回来**：
 *    ```smali
 *    LJI():
 *        textView.setText(...)            // 这里识别到并标记
 *        textView.setVisibility(VISIBLE)  // 紧接着又被显示回来
 *    ```
 *  - **控件可能在布局里默认就是 VISIBLE 的**：这类控件代码从头到尾只「藏」不「显示」，
 *    拦掉显示方法等于什么都没做，它照样亮着。
 *    聊天里的发送状态图标（红叹号）就是这样：它只由基类显示，
 *    而那个 ImageView 是聊天 cell 布局里就有的。
 *
 * 所以识别层只负责**登记**，真正的压制交给 [View.setVisibility] 那一层 ——
 * 只要控件在登记表里，任何 VISIBLE 请求都改写成 GONE。
 *
 * ## 为什么按实例登记，而不是按资源 id
 *
 * 旧版是按资源 id 拉黑名单，踩过两次坑：
 *   - `0x7f0aa9d7` 同时也是会话列表的标题，加进黑名单后**消息页的会话名全没了**
 *   - 资源 id 是 aapt 打包时分配的，**抖音升级就可能变**
 *
 * 按实例登记只压住**自己抓到的那个 View 对象**，不可能误伤别的界面。
 *
 * ## 开销（setVisibility 是极热路径）
 *
 *   - 先比 `requested == GONE` 直接放行（一半以上的调用走这条）
 *   - 再做一次 WeakHashMap 查找；表很小（几个到几十个控件）
 *   - 查找未命中时零额外开销
 *   - 跳过 EditText，否则用户自己打「封禁」两个字输入框会当场消失
 */
internal object TextHider {

    /** RestrictionGuard 登记的控件（例如聊天里的发送状态图标）。 */
    const val FLAG_TIP_ICON = 1

    /** 命中内置限制词的文字。 */
    const val FLAG_TIP_TEXT = 2

    /** 命中用户自定义关键词的文字。 */
    const val FLAG_KEYWORD = 4

    /** 控件 -> 标记。WeakHashMap 保证控件被回收后自动出表，不泄漏 View。 */
    private val hidden: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    private val logged = ConcurrentHashMap.newKeySet<String>()

    private val squashCount = AtomicInteger(0)

    /** 压住发送失败图标的次数。 */
    private val iconHits = AtomicInteger(0)

    fun install(module: XposedModule, rules: RuleSource) {
        hookSetText(module, rules)
        hookSetVisibility(module, rules)
    }

    /**
     * 登记一个「无论调用方怎么显示都要藏住」的控件。
     *
     * @return 是否是第一次登记（调用方据此只打一次日志、只按一次 GONE）
     */
    fun markTip(view: View): Boolean = mark(view, FLAG_TIP_ICON)

    private fun mark(view: View, flag: Int): Boolean {
        synchronized(hidden) {
            val old = hidden[view] ?: 0
            if (old and flag != 0) return false
            hidden[view] = old or flag
            return true
        }
    }

    // ---------------------------------------------------------------- 登记层

    private fun hookSetText(module: XposedModule, rules: RuleSource) {
        val method = runCatching {
            TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        }.getOrNull()
        if (method == null) {
            Diag.log("text", "找不到 TextView.setText(CharSequence)")
            return
        }

        runCatching {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()

                val view = chain.thisObject as? TextView
                val text = chain.args.getOrNull(0) as? CharSequence
                if (view != null && text != null && !isEditable(view)) {
                    rules.tick()

                    // 内置限制词优先：它服务于默认开启的那个开关
                    val byTips = rules.hideTips() && rules.matchesBuiltin(text)
                    val byKeyword = !byTips && rules.keywordHiding() && rules.matchesKeyword(text)

                    if (byTips) mark(view, FLAG_TIP_TEXT)
                    if (byKeyword) mark(view, FLAG_KEYWORD)

                    if (byTips || byKeyword) {
                        // 限制提示整条藏掉；关键词兜底只动命中的那一处，
                        // 因为用户自己写的词边界不可预期，别替他扩大范围
                        val target = if (byTips) bannerTarget(view) else view
                        if (target !== view) mark(target, FLAG_TIP_TEXT)
                        target.visibility = View.GONE
                        logOnce(if (byTips) "限制提示" else "关键词", text)
                    }
                }

                result
            }
            Diag.log("text", "已挂钩 TextView.setText(CharSequence)")
        }.onFailure { Diag.log("text", "TextView.setText 挂载失败: $it") }
    }

    // ---------------------------------------------------------------- 压制层

    private fun hookSetVisibility(module: XposedModule, rules: RuleSource) {
        val method = runCatching {
            View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (method == null) {
            Diag.log("text", "找不到 View.setVisibility(int)")
            return
        }

        runCatching {
            module.hook(method).intercept { chain ->
                // 已经是隐藏态就放行，先走最短路径（一半以上的调用走这条）
                val requested = chain.args[0] as Int
                if (requested == View.GONE) return@intercept chain.proceed()

                val view = chain.thisObject as? View ?: return@intercept chain.proceed()

                // 聊天里那条「发送失败」的红色叹号（见 Targets.SEND_FAIL_ICON_ID）。
                // 先比 int id —— 对绝大多数 View 来说这一步不成立，直接放行，
                // 只有命中时才去比类名，热路径上几乎零成本。
                if (view.id == Targets.SEND_FAIL_ICON_ID && rules.hideTips() &&
                    view.javaClass.name.startsWith(Targets.SEND_FAIL_ICON_CLASS)
                ) {
                    if (iconHits.incrementAndGet() <= SQUASH_LOG_LIMIT) {
                        Diag.log("text", "压住聊天发送失败图标 #0x%08x".format(view.id))
                    }
                    return@intercept chain.proceed(arrayOf<Any?>(View.GONE))
                }

                val flags = hidden[view] ?: return@intercept chain.proceed()

                val allowTips = flags and (FLAG_TIP_ICON or FLAG_TIP_TEXT) != 0 && rules.hideTips()
                val allowKeyword = flags and FLAG_KEYWORD != 0 && rules.keywordHiding()
                if (!allowTips && !allowKeyword) return@intercept chain.proceed()

                if (squashCount.incrementAndGet() <= SQUASH_LOG_LIMIT) {
                    Diag.debug("text", "压住 ${view.javaClass.simpleName} 的显示请求（flags=$flags）")
                }
                // 把「显示」改写成 GONE
                chain.proceed(arrayOf<Any?>(View.GONE))
            }
            Diag.log("text", "已挂钩 View.setVisibility(int)（压制层）")
        }.onFailure { Diag.log("text", "View.setVisibility 挂载失败: $it") }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 找到应该隐藏的「整条提示」。
     *
     * 只藏文字会留下孤零零的图标 —— 例如「由于违反社区规定，你的私信功能暂被封禁」
     * 那条横幅前面就有一个红色叹号。把文字藏掉、图标留在那儿，看起来更奇怪，
     * 跟吐司不能只藏 TextView（会剩一个空药丸壳）是同一个道理。
     *
     * 判据刻意定得很保守：**只有当这个容器里除了它没有别的文字时**，才把整条藏掉。
     * 这样最多连带藏掉同一行的图标，绝不会把不该藏的内容一起带走。
     */
    private fun bannerTarget(view: TextView): View {
        val parent = view.parent as? ViewGroup ?: return view
        if (parent.childCount > BANNER_MAX_CHILDREN) return view
        if (countTexts(parent, TEXT_WALK_BUDGET) > 1) return view
        return parent
    }

    /** 子树里非空 TextView 的个数。有节点预算上限，避免在超长容器上爆开销。 */
    private fun countTexts(root: View, budget: Int): Int {
        var remaining = budget
        var count = 0
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty() && remaining-- > 0) {
            val current = stack.removeLast()
            if (current is TextView && current.text?.isNotEmpty() == true) count++
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) stack.addLast(current.getChildAt(i))
            }
        }
        return count
    }

    /** 用户输入框不能动：否则自己打「封禁」两个字，输入框就没了。 */
    private fun isEditable(view: TextView): Boolean = view is EditText

    /** 同一条文案只报一次，避免滚动时刷屏。 */
    private fun logOnce(rule: String, text: CharSequence) {
        val sample = text.toString().take(40)
        if (logged.size < 40 && logged.add(sample)) {
            Diag.log("text", "按[$rule]隐藏：$sample")
        }
    }

    private const val SQUASH_LOG_LIMIT = 30

    /** 超过这个子控件数就不当成「一条提示」，避免误伤列表项。 */
    private const val BANNER_MAX_CHILDREN = 4

    private const val TEXT_WALK_BUDGET = 24
}

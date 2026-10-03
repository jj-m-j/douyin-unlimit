package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 隐藏界面元素。两种登记来源，共用一个「压制」出口：
 *
 *  1. **关键词文字**（本对象自己判定）：`TextView.setText` 命中关键词 -> 登记 + GONE
 *  2. **被点名的控件**（[markTip]，由 RestrictionGuard 调用）：例如聊天里的发送状态图标
 *
 * 两者都必须走同一个压制层，原因见下。
 *
 * ## 为什么「只拦显示方法」不够，必须补一层 setVisibility 压制
 *
 * 两个独立的现实：
 *
 *  - **调用方会在 setText 之后再把控件显示回来**：
 *    ```smali
 *    LJI():
 *        textView.setText(...)            // 这里识别到并标记
 *        textView.setVisibility(VISIBLE)  // 紧接着又被显示回来
 *    ```
 *  - **控件可能在布局里默认就是 VISIBLE 的**：这类控件代码从头到尾只「藏」不「显示」，
 *    拦掉显示方法等于什么都没做，它照样亮着。聊天里的发送状态图标（红叹号）正是如此：
 *    它只由基类 `LX/179c.LIZ()` 显示，而那个 ImageView 是聊天 cell 布局里就有的，
 *    构造 `StatusIconWithText` 时被传进来。
 *
 * 所以 setText / 显示方法只负责**登记**，真正的压制交给 [View.setVisibility] 那一层 ——
 * 只要控件在登记表里，任何 VISIBLE 请求都改写成 GONE。
 *
 * ## 为什么按实例登记，而不是按资源 id
 *
 * 旧版是按资源 id 拉黑名单，踩过两次坑：
 *   - `0x7f0aa9d7` 同时也是会话列表的标题，加进黑名单后**消息页的会话名全没了**
 *   - 资源 id 是 aapt 打包时分配的，**抖音升级就可能变**
 *
 * 按实例登记没有这两个问题：只压住**自己抓到的那个 View 对象**，不可能误伤别的界面，
 * 抖音升级也照样有效。
 *
 * ## 开销（setVisibility 是极热路径）
 *
 *   - 先比 `requested == GONE` 直接放行（一半以上的调用走这条）
 *   - 再做一次 WeakHashMap 查找；两张表都很小（几个到几十个控件）
 *   - 只有命中登记表才会去读开关，查找未命中时零额外开销
 *   - 跳过 EditText，否则用户自己打「封禁」两个字输入框会当场消失
 */
internal object TextHider {

    /** 关键词命中的控件。 */
    private val keywordViews: MutableSet<View> = newWeakSet()

    /** 被 [markTip] 点名的控件（RestrictionGuard 登记的发送状态图标等）。 */
    private val tipViews: MutableSet<View> = newWeakSet()

    private val logged = ConcurrentHashMap.newKeySet<String>()

    private val squashCount = AtomicInteger(0)

    private fun newWeakSet(): MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    fun install(module: XposedModule, rules: RuleSource) {
        hookSetText(module, rules)
        hookSetVisibility(module, rules)
    }

    /**
     * 登记一个「无论调用方怎么显示都要藏住」的控件。
     *
     * RestrictionGuard 用它压住聊天里的发送状态图标：那个 ImageView 来自 cell 布局，
     * 可能在 XML 里就是 VISIBLE，光拦「显示方法」压不住。
     *
     * @return 是否是第一次登记（调用方据此只打一次日志、只按一次 GONE）
     */
    fun markTip(view: View): Boolean = tipViews.add(view)

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
                if (view != null && text != null && !isEditable(view) &&
                    rules.textHidingOn() && rules.shouldHideText(text)
                ) {
                    keywordViews.add(view)
                    view.visibility = View.GONE
                    logOnce(text)
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

                val squash = (tipViews.contains(view) && rules.hideTips()) ||
                    (keywordViews.contains(view) && rules.textHidingOn())

                if (!squash) return@intercept chain.proceed()

                if (squashCount.incrementAndGet() <= SQUASH_LOG_LIMIT) {
                    Diag.debug("text", "压住 ${view.javaClass.name} 的显示请求")
                }
                // 把「显示」改写成 GONE
                chain.proceed(arrayOf<Any?>(View.GONE))
            }
            Diag.log("text", "已挂钩 View.setVisibility(int)（压制层）")
        }.onFailure { Diag.log("text", "View.setVisibility 挂载失败: $it") }
    }

    // ---------------------------------------------------------------- 工具

    /** 用户输入框不能动：否则自己打「封禁」两个字，输入框就没了。 */
    private fun isEditable(view: TextView): Boolean = view is EditText

    /** 同一个关键词只报一次，避免滚动时刷屏。 */
    private fun logOnce(text: CharSequence) {
        val sample = text.toString().take(40)
        if (logged.size < 30 && logged.add(sample)) {
            Diag.debug("text", "隐藏文字：$sample")
        }
    }

    private const val SQUASH_LOG_LIMIT = 20
}

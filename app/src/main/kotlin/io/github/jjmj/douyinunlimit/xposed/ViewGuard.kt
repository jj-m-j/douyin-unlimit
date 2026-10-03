package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 按控件 id 隐藏界面元素。
 *
 * 需要两个拦截层，缺一不可：
 *
 *  1. ViewGroup.addView / LayoutInflater.inflate —— 控件「刚被加进来」时按住。
 *     很多控件是在 XML 里声明、默认就 VISIBLE 的，代码从头到尾不会调用 setVisibility
 *     （整行是「加进去就显示」而不是「显示/隐藏切换」），只拦 setVisibility 会完全漏掉。
 *
 *  2. View.setVisibility —— 控件被复用 / 重新绑定时按住。
 *     聊天列表是 RecyclerView，控件会被回收复用，每次 rebind 都会重新 toggle 可见性。
 *     例如 StatusIconWithText：
 *       LJI():  message.msgStatus < 2 -> textView.setVisibility(VISIBLE)
 *       LIZJ(): ImageView.setVisibility(GONE); textView.setVisibility(INVISIBLE); LJI()
 *     所以只在 addView 时隐藏会被下一次 rebind 覆盖。
 *
 * 代价：这两条都是热路径。控制手段——
 *   - 配置摊平成 IntArray，命中判断只做线性扫描，无装箱无分配
 *   - 未命中且黑名单为空时立刻 proceed
 *   - 子树遍历有节点预算上限，避免在超长列表项上爆开销
 *   - 日志只在每个 id 第一次命中时打一条
 */
internal object ViewGuard {

    private const val TAG = "DouyinUnlimit"

    /** 单次子树遍历的节点上限，超出就放弃（正常列表项远小于这个数）。 */
    private const val WALK_BUDGET = 400

    fun install(module: XposedModule, rules: RuleSource) {
        val hooked = mutableListOf<String>()

        hooked += hookSetVisibility(module, rules).orEmpty()
        hooked += hookAddView(module, rules)
        hooked += hookInflate(module, rules)

        if (hooked.isEmpty()) {
            module.log(Log.WARN, TAG, "ViewGuard: 没有挂上任何 hook，控件隐藏不会生效")
        } else {
            module.log(Log.INFO, TAG, "ViewGuard hooked: ${hooked.joinToString(" | ")}")
        }
    }

    // ---------------------------------------------------------------- setVisibility

    private fun hookSetVisibility(module: XposedModule, rules: RuleSource): List<String> {
        val method = runCatching {
            View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return emptyList()

        return runCatching {
            module.hook(method).intercept { chain ->
                if (!rules.hideViewsEnabled()) return@intercept chain.proceed()

                val view = chain.thisObject as? View
                if (view == null) return@intercept chain.proceed()

                // 命中 id 黑名单，或已被 TextGuard 标记（文字里含关键词）
                if (!BlockedViews.isBlocked(view) && !rules.shouldHideView(view.id)) {
                    return@intercept chain.proceed()
                }

                val requested = chain.args[0] as Int
                if (requested == View.GONE) return@intercept chain.proceed()

                // 把任何「显示」请求改写成 GONE
                logOnce(view.id, "setVisibility($requested) on ${view.javaClass.name}")
                chain.proceed(arrayOf<Any?>(View.GONE))
            }
            listOf("View.setVisibility")
        }.getOrElse { emptyList() }
    }

    // ---------------------------------------------------------------- addView

    private fun hookAddView(module: XposedModule, rules: RuleSource): List<String> {
        val group = ViewGroup::class.java
        val intType = Int::class.javaPrimitiveType!!
        val paramsType = ViewGroup.LayoutParams::class.java
        val candidates = listOfNotNull(
            runCatching { group.getDeclaredMethod("addView", View::class.java) }.getOrNull(),
            runCatching { group.getDeclaredMethod("addView", View::class.java, intType) }.getOrNull(),
            runCatching { group.getDeclaredMethod("addView", View::class.java, intType, paramsType) }.getOrNull(),
            runCatching { group.getDeclaredMethod("addView", View::class.java, intType, paramsType, intType) }.getOrNull(),
        )

        val hooked = mutableListOf<String>()
        for (method in candidates) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (rules.hideViewsEnabled()) {
                        val child = chain.args[0] as? View
                        if (child != null) applyHide(child, rules)
                    }
                    chain.proceed()
                }
                hooked += "ViewGroup.addView/${method.parameterCount}"
            }
        }
        return hooked
    }

    // ---------------------------------------------------------------- inflate

    private fun hookInflate(module: XposedModule, rules: RuleSource): List<String> {
        val inflater = LayoutInflater::class.java
        val candidates = listOfNotNull(
            runCatching { inflater.getDeclaredMethod("inflate", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching {
                inflater.getDeclaredMethod("inflate", Int::class.javaPrimitiveType, ViewGroup::class.java)
            }.getOrNull(),
        )

        val hooked = mutableListOf<String>()
        for (method in candidates) {
            runCatching {
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (rules.hideViewsEnabled() && result is View) {
                        applyHide(result, rules)
                    }
                    result
                }
                hooked += "LayoutInflater.inflate/${method.parameterCount}"
            }
        }
        return hooked
    }

    // ---------------------------------------------------------------- 共用

    /** 遍历子树，把黑名单里的控件强制设为 GONE。 */
    private fun applyHide(root: View, rules: RuleSource) {
        var budget = WALK_BUDGET
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty() && budget-- > 0) {
            val view = stack.removeLast()
            if (rules.shouldHideView(view.id)) {
                if (view.visibility != View.GONE) {
                    logOnce(view.id, "addView/inflate hid ${view.javaClass.name}")
                }
                view.visibility = View.GONE
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    stack.addLast(view.getChildAt(i))
                }
            }
        }
    }

    private val logged = ConcurrentHashMap.newKeySet<Int>()

    /** 只在每个 id 第一次命中时打日志，避免滚动时刷屏。 */
    private fun logOnce(id: Int, detail: String) {
        if (logged.add(id)) {
            Log.i(TAG, "hide view id=0x%08x  %s".format(id, detail))
        }
    }
}

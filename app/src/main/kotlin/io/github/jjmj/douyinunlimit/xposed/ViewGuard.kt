package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 按控件 id 隐藏界面元素。
 *
 * 为什么拦 setVisibility，而不是在 inflate 时隐藏：
 * 聊天列表是 RecyclerView，控件会被复用，每次 rebind 都会重新 toggle 可见性。
 * 例如 StatusIconWithText（发送状态组件）在绑定时会这么干：
 *
 *   LJI():  message.msgStatus < 2 -> textView.setText(...); textView.setVisibility(VISIBLE)
 *   LIZJ(): ImageView.setVisibility(GONE); textView.setVisibility(INVISIBLE); LJI()
 *
 * 所以在 inflate/adapter 层隐藏会被下一次 rebind 覆盖，拦 setVisibility 才拦得住。
 *
 * 代价：setVisibility 是热路径。这里做了两件事把开销压到最低——
 *   1. 配置摊平成 IntArray，命中判断只做一次线性扫描，无装箱无分配
 *   2. 未命中时直接 proceed，不做任何多余工作
 */
internal object ViewGuard {

    private const val TAG = "DouyinUnlimit"

    fun install(module: XposedModule, rules: RuleSource) {
        val setVisibility = runCatching {
            View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return

        runCatching {
            module.hook(setVisibility).intercept { chain ->
                if (!rules.hideViews()) return@intercept chain.proceed()

                rules.syncViewIds()
                val view = chain.thisObject as? View
                if (view == null || !rules.shouldHideView(view.id)) {
                    return@intercept chain.proceed()
                }

                val requested = chain.args[0] as Int
                if (requested == View.GONE) return@intercept chain.proceed()

                // 把任何「显示」请求改写成 GONE，这样无论哪条代码路径把它显示出来都拦得住
                logOnce(view.id, requested, view.javaClass.name)
                chain.proceed(arrayOf<Any?>(View.GONE))
            }
        }
    }

    private val logged = ConcurrentHashMap.newKeySet<Int>()

    /** 只在每个 id 第一次命中时打日志，避免滚动时刷屏。 */
    private fun logOnce(id: Int, requested: Int, className: String) {
        if (logged.add(id)) {
            Log.i(TAG, "hide view id=0x%08x (%s), requested visibility=%d".format(id, className, requested))
        }
    }
}

package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 按关键词隐藏文字控件。
 *
 * ## 为什么要有这一层
 *
 * 抖音的限制类文案大多是**服务端下发**的，既不在 dex 字符串里也不在资源表里，
 * 而且同一类提示会散落在不同位置（会话里的系统消息、聊天里的提示行、列表里的横幅……）。
 * 按控件 id 一个个点名是打地鼠，而且一个资源 id 还可能被别的界面复用（踩过这个坑：
 * 0x7f0aa9d7 同时也是会话列表的标题，加进黑名单后消息页的会话名全没了）。
 *
 * 所以改成按**运行时文字内容**判定，和吐司共用同一份关键词表。
 *
 * ## 必须两层拦截，缺一不可
 *
 *   TextView.setText(CharSequence) -> 命中关键词就登记进 [blocked] 并立即 GONE
 *   View.setVisibility(int)         -> 控件在登记表里，就把任何 VISIBLE 请求改写成 GONE
 *
 * 原因是调用方经常在 setText **之后**再补一次显示，例如：
 *
 *   LJI():
 *       textView.setText(...)             // 这里识别到并标记
 *       textView.setVisibility(VISIBLE)   // 紧接着又被显示回来 <- 只拦 setText 挡不住
 *
 * ## 开销
 *
 * setText / setVisibility 是极热路径，控制手段：
 *   - 开关判断走 [RuleSource.textHidingOn]（计数器节流，纯内存）
 *   - 文字短于最短关键词直接跳过扫描
 *   - 跳过 EditText：否则用户自己打「封禁」两个字，输入框会当场消失
 *   - 命中判定是纯数组扫描，无装箱无分配
 *   - 每个关键词只在第一次命中时写一条日志
 */
internal object TextHider {

    /** 判定为「要隐藏」的控件。WeakHashMap 保证控件被回收后自动出表，不泄漏 View。 */
    private val blocked: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    private val logged = ConcurrentHashMap.newKeySet<String>()

    fun install(module: XposedModule, rules: RuleSource) {
        hookSetText(module, rules)
        hookSetVisibility(module, rules)
    }

    // ---------------------------------------------------------------- 第一层

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
                    blocked.add(view)
                    view.visibility = View.GONE
                    logOnce(text)
                }

                result
            }
            Diag.log("text", "已挂钩 TextView.setText(CharSequence)")
        }.onFailure { Diag.log("text", "TextView.setText 挂载失败: $it") }
    }

    // ---------------------------------------------------------------- 第二层

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
                val requested = chain.args[0] as Int
                // 已经是隐藏态就不用管了，先走最短路径
                if (requested == View.GONE) return@intercept chain.proceed()

                val view = chain.thisObject as? View
                if (view == null || !blocked.contains(view)) return@intercept chain.proceed()
                if (!rules.textHidingOn()) return@intercept chain.proceed()

                // 把任何「显示」请求改写成 GONE
                chain.proceed(arrayOf<Any?>(View.GONE))
            }
            Diag.log("text", "已挂钩 View.setVisibility(int)（压住被标记的控件）")
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
}

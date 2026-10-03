package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 按关键词隐藏文字控件。
 *
 * 为什么要有这一层：抖音的封禁类提示文案大多是**服务端下发**的，
 * 既不在 dex 字符串里也不在资源表里，而且同一类提示会散落在不同位置
 * （会话里的系统消息、聊天里的提示行、列表里的横幅……）。
 * 按控件 id 一个个点名是打地鼠，而且一个资源 id 还可能被别的界面复用（踩过这个坑）。
 *
 * 这里改成按**运行时文字内容**判定，复用吐司那套关键词表：
 *   - setText 时识别：命中关键词就登记进 [BlockedViews] 并立即 GONE
 *   - 后续任何 setVisibility(VISIBLE) 都会被 ViewGuard 改写成 GONE
 *
 * 开销控制（setText 是很热的路径）：
 *   - 总开关走节流缓存，未开启立刻返回
 *   - 文本长度小于最短关键词直接跳过
 *   - 跳过 EditText：否则用户自己输入「封禁」两个字，输入框会自己消失
 *   - 只在每个关键词第一次命中时打日志
 */
internal object TextGuard {

    private const val TAG = "DouyinUnlimit"

    fun install(module: XposedModule, rules: RuleSource) {
        val method = runCatching {
            TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        }.getOrNull()

        if (method == null) {
            module.log(Log.WARN, TAG, "TextGuard: 找不到 TextView.setText(CharSequence)")
            return
        }

        runCatching {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()

                val view = chain.thisObject as? TextView
                val text = chain.args[0] as? CharSequence
                if (view != null && text != null && !isEditable(view) && rules.shouldHideText(text)) {
                    BlockedViews.mark(view)
                    view.visibility = View.GONE
                    logOnce(text)
                }

                result
            }
            module.log(Log.INFO, TAG, "TextGuard hooked: TextView.setText(CharSequence)")
        }.onFailure {
            module.log(Log.ERROR, TAG, "TextGuard 挂载失败", it)
        }
    }

    /** 用户输入框不能动：否则自己打「封禁」两个字，输入框就没了。 */
    private fun isEditable(view: TextView): Boolean = view is EditText

    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** 同一个关键词只报一次，避免滚动时刷屏。 */
    private fun logOnce(text: CharSequence) {
        val sample = text.toString().take(40)
        if (logged.size < 20 && logged.add(sample)) {
            Log.i(TAG, "hide text: $sample")
        }
    }
}

package io.github.jj_m_j.douyinunlimit.xposed

import android.view.View
import android.view.ViewParent
import io.github.libxposed.api.XposedModule

/**
 * 点击探针：把每次点击的**控件类名 + id + 完整祖先链**记下来。
 *
 * 这是「详细调试日志」里最有用的一项——当不知道某个按钮到底是哪个控件时，
 * 打开它、点一下那个按钮，日志里就直接写出了它的类名和 id，
 * 不用再靠 Layout Inspect 猜、也不用一轮轮试。
 *
 * 输出示例：
 *   [click*] 点击 android.widget.ImageView #0x7f0a309d
 *           ← FrameLayout(0x7f0abf56) < ConstraintLayout < VideoDiggLayout < ...
 *
 * 只在「详细调试日志」开启时记录，且有采样上限，避免刷屏。
 */
internal object ClickProbe {

    private const val MAX_ANCESTORS = 10
    private const val SAMPLE_LIMIT = 80

    private var count = 0

    fun install(module: XposedModule, rules: RuleSource) {
        val performClick = runCatching {
            View::class.java.getDeclaredMethod("performClick")
        }.getOrNull()
        if (performClick == null) {
            Diag.log("click", "找不到 View.performClick")
            return
        }

        runCatching {
            module.hook(performClick).intercept { chain ->
                if (count < SAMPLE_LIMIT) {
                    val view = chain.thisObject as? View
                    if (view != null) {
                        count++
                        Diag.debug("click", describe(view))
                    }
                }
                chain.proceed()
            }
            Diag.log("click", "已挂钩 View.performClick（点击探针，需开启详细调试日志）")
        }.onFailure {
            Diag.log("click", "ClickProbe 挂载失败: $it")
        }
    }

    private fun describe(view: View): String {
        val sb = StringBuilder("点击 ")
        sb.append(view.javaClass.name)
        if (view.id != 0) sb.append(" #0x").append(Integer.toHexString(view.id))
        sb.append("  ← ")
        var parent: ViewParent? = view.parent
        var level = 0
        while (parent is View && level < MAX_ANCESTORS) {
            sb.append(parent.javaClass.simpleName)
            if (parent.id != 0) sb.append("(0x").append(Integer.toHexString(parent.id)).append(')')
            sb.append(" < ")
            parent = parent.parent
            level++
        }
        return sb.toString()
    }
}

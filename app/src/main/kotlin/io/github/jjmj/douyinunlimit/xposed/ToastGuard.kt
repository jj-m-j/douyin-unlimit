package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 抖音吐司拦截。
 *
 * 逆向后确认，抖音的提示吐司由 DUX Toast 体系负责：
 *
 *   DuxToastV2.LIZJ(context, icon, iconTint, text, ..., style, ...)
 *       ├─ style == DuxCustom -> new PopupToast(context, view)   // 自绘浮层，屏幕中上部那种
 *       └─ 否则               -> new DuxSystemToast(context)     // 系统 Toast + setView
 *
 * 两条显示路径都从同一个构造方法分叉，所以这里以它为收口点：拿到 CharSequence 参数，
 * 命中关键词就直接返回 null，两种样式一起挡掉。旧版 DuxToast 结构相同，一并处理。
 *
 * 另外还挂了 android.widget.Toast#show 作为兜底，覆盖不经过 DUX 的零散调用。
 */
internal object ToastGuard {

    private val DUX_TOAST_CLASSES = listOf(
        "com.bytedance.dux.toast.DuxToastV2",
        "com.bytedance.dux.toast.DuxToast",
    )

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookSystemToast(module, rules)
        for (className in DUX_TOAST_CLASSES) {
            hookDuxToast(module, loader, className, rules)
        }
    }

    // ---------------------------------------------------------------- system toast

    private fun hookSystemToast(module: XposedModule, rules: RuleSource) {
        val show = runCatching { Toast::class.java.getDeclaredMethod("show") }.getOrNull() ?: return
        runCatching {
            module.hook(show).intercept { chain ->
                val toast = chain.thisObject as? Toast
                if (toast != null && rules.shouldBlock(textOf(toast))) {
                    return@intercept null
                }
                chain.proceed()
            }
        }
    }

    /** 系统 Toast 的文案可能挂在 getText() 上，也可能藏在 setView() 进来的自定义布局里。 */
    private fun textOf(toast: Toast): String {
        val out = StringBuilder()
        runCatching { toastTextOf(toast)?.let { out.append(it) } }
        runCatching { toast.view?.let { collectText(it, out) } }
        return out.toString()
    }

    /** Toast#getText() 在 SDK stub 里不是公开 API，走反射拿。 */
    private fun toastTextOf(toast: Toast): CharSequence? =
        runCatching {
            Toast::class.java.getMethod("getText").invoke(toast) as? CharSequence
        }.getOrNull()

    private fun collectText(view: View, out: StringBuilder) {
        when (view) {
            is TextView -> view.text?.let { out.append(it).append('\n') }
            is ViewGroup -> for (i in 0 until view.childCount) {
                collectText(view.getChildAt(i), out)
            }
        }
    }

    // ---------------------------------------------------------------- dux toast

    /**
     * DUX 的类名是稳定的，但方法名多被混淆（LIZJ / LJ / LJFF ...）。这里不写死名字，
     * 而是遍历所有「接收 CharSequence 且返回 void 或引用类型」的方法挂 hook，
     * 这样抖音升级改名也照样能命中。
     */
    private fun hookDuxToast(
        module: XposedModule,
        loader: ClassLoader,
        className: String,
        rules: RuleSource,
    ) {
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return

        for (method in clazz.declaredMethods) {
            if (!takesText(method)) continue
            if (!returnsVoidOrReference(method)) continue
            runCatching {
                module.hook(method).intercept { chain ->
                    for (arg in chain.args) {
                        if (arg is CharSequence && rules.shouldBlock(arg.toString())) {
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun takesText(method: Method): Boolean = method.parameterTypes.any {
        it == CharSequence::class.java || it == String::class.java
    }

    /** 返回基本类型的方法不能返回 null，跳过它们避免破坏调用方。 */
    private fun returnsVoidOrReference(method: Method): Boolean {
        val returnType = method.returnType
        return returnType == Void.TYPE || !returnType.isPrimitive
    }
}

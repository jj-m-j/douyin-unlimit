package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 「隐藏限制提示」——把抖音宣告「你被限制了」的三种载体一起吞掉。
 *
 * 这三处在代码里是三套完全不同的实现（v1.13 里是三个独立开关 + 三个文件），
 * 但用户看到的是同一件事，所以合并成一个开关：
 *
 *   1. 弹窗吐司        DUX Toast 体系（DuxToastV2 / 旧版 DuxToast）+ 系统 Toast 兜底
 *   2. 消息页顶部横幅  ChatBanTipsLogic（整个类只服务这一条横幅）
 *   3. 聊天发送状态    StatusIconWithText（红感叹号 + 说明文字）
 *
 * ## 为什么吐司必须在「生成之前」拦掉，不能只靠藏文字
 *
 * 自绘吐司是一个 PopupToast 浮层。把里面的 TextView 藏掉，只会剩下一个**空药丸壳**，
 * 看起来更奇怪。所以必须在吐司对象被创建之前就从源头返回，让它根本不出现。
 * （这也是「抹掉带关键词的文字」不能取代吐司开关的原因。）
 *
 * ## 为什么用「泛化扫描」而不是写死方法名
 *
 * DUX 的类名稳定，但方法名大量被混淆，而且**收口点不止一个**：
 *
 *   系统吐司 LIZJ(...CharSequence...)         <- static，参数第 3 位是文案
 *   自绘吐司 LIZLLL(...DuxToastContent...)    <- 文案在 DuxToastContent 里，拿不到
 *   自绘入口 LJ(Context, boolean, String, ..) <- 这里是 String
 *   便捷入口 LJFF(Context, CharSequence) / makeShowSystemToast$default(...)
 *   上层封装 makeShowCustomToast$default(...) / customToastShow$default(...)
 *
 * 逐个写死等于跟着抖音版本赛跑。所以改成：遍历这些类里「参数含 CharSequence 或
 * String」的方法，命中关键词就拦。这样即使抖音改方法名也照样工作。
 *
 * ## 返回 null 的风险与处理
 *
 * 有些吐司方法返回 `IDuxToastOperation`，调用方之后会拿它 `dismiss()`。直接返回
 * null 会让调用方 NPE。所以返回类型是接口时，回一个**什么都不做的动态代理**代替 null。
 */
internal object RestrictionGuard {

    private val TOAST_CLASSES = listOf(
        "com.bytedance.dux.toast.DuxToastV2",
        "com.bytedance.dux.toast.DuxToast",
        "com.bytedance.dux.toast.DuxToastContent\$DuxToastTextContent",
    )

    private const val IM_BAN_TIPS_LOGIC =
        "com.ss.android.ugc.aweme.im.sdk.module.session.rips.sessionheader.tips.ChatBanTipsLogic"

    private const val SEND_STATUS =
        "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.status.sendstatus.StatusIconWithText"

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookSystemToast(module, rules)
        for (name in TOAST_CLASSES) hookDuxToast(module, loader, name, rules)

        // 消息页横幅需要拦两个入口：
        //   LJLLLLLL()V 是「显示还是隐藏」的判定（读 ImBanInfo + 已展示记录）
        //   LJJJLZIJ()V 是真正把横幅挂上去的方法（里面有 im_message_block_notice_show 埋点）
        // 只拦判定的话，别处直接调显示入口就绕过去了。
        hookShowMethods(
            module, loader, IM_BAN_TIPS_LOGIC,
            names = listOf("LJLLLLLL", "LJJJLZIJ"),
            label = "消息页横幅",
            enabled = { rules.hideTips() },
        )

        // 聊天发送状态：只拦「显示」，不动 LIZJ / LIZLLL 这些隐藏路径，
        // 避免和它自己的状态机打架。
        hookShowMethods(
            module, loader, SEND_STATUS,
            names = listOf("LIZ", "LJI"),
            label = "聊天发送状态",
            enabled = { rules.hideTips() },
        )
    }

    // ---------------------------------------------------------------- 系统 Toast

    private fun hookSystemToast(module: XposedModule, rules: RuleSource) {
        val show = runCatching { Toast::class.java.getDeclaredMethod("show") }.getOrNull()
        if (show == null) {
            Diag.log("tips", "找不到 Toast.show")
            return
        }

        runCatching {
            module.hook(show).intercept { chain ->
                val toast = chain.thisObject as? Toast
                if (toast != null && rules.hideTips() && rules.shouldBlockToast(textOf(toast))) {
                    return@intercept null
                }
                chain.proceed()
            }
            Diag.log("tips", "系统 Toast 兜底已挂钩")
        }.onFailure { Diag.log("tips", "Toast.show 挂载失败: $it") }
    }

    /** 系统 Toast 的文案可能挂在 getText() 上，也可能藏在 setView() 进来的自定义布局里。 */
    private fun textOf(toast: Toast): String {
        val out = StringBuilder()
        runCatching {
            Toast::class.java.getMethod("getText").invoke(toast)?.let { out.append(it) }
        }
        runCatching { toast.view?.let { collectText(it, out) } }
        return out.toString()
    }

    private fun collectText(view: View, out: StringBuilder) {
        when (view) {
            is TextView -> view.text?.let { out.append(it).append('\n') }
            is ViewGroup -> for (i in 0 until view.childCount) {
                collectText(view.getChildAt(i), out)
            }
        }
    }

    // ---------------------------------------------------------------- DUX Toast

    private fun hookDuxToast(
        module: XposedModule,
        loader: ClassLoader,
        className: String,
        rules: RuleSource,
    ) {
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
        if (clazz == null) {
            Diag.log("tips", "找不到 $className")
            return
        }

        val hooked = mutableListOf<String>()
        for (method in clazz.declaredMethods) {
            if (!takesText(method) || !returnsVoidOrReference(method)) continue
            runCatching {
                module.hook(method).intercept { chain ->
                    if (!rules.hideTips()) return@intercept chain.proceed()
                    for (arg in chain.args) {
                        if (arg is CharSequence && rules.shouldBlockToast(arg.toString())) {
                            Diag.debug("tips", "拦下吐司：${arg.toString().take(40)}")
                            return@intercept skipValue(method.returnType)
                        }
                    }
                    chain.proceed()
                }
                hooked += method.name
            }
        }

        Diag.log(
            "tips",
            if (hooked.isEmpty()) {
                "$className 里没有收 CharSequence 的公共入口（抖音版本可能变了）"
            } else {
                "$className 已挂钩 ${hooked.size} 个吐司入口: ${hooked.joinToString("/")}"
            },
        )
    }

    private fun takesText(method: Method): Boolean = method.parameterTypes.any {
        it == CharSequence::class.java || it == String::class.java
    }

    /** 返回基本类型的方法不能返回 null，跳过它们避免破坏调用方。 */
    private fun returnsVoidOrReference(method: Method): Boolean =
        method.returnType == Void.TYPE || !method.returnType.isPrimitive

    // ---------------------------------------------------------------- 显示方法

    /**
     * 让「把东西显示出来」的方法不执行。
     *
     * 抖音的类名稳定，但方法名被混淆了，所以先按记下来的名字找，
     * 找不到就退化成「所有 public 无参 void 方法」——这两个类各自只服务一个组件，
     * 全屏蔽也没有副作用。
     */
    private fun hookShowMethods(
        module: XposedModule,
        loader: ClassLoader,
        className: String,
        names: List<String>,
        label: String,
        enabled: () -> Boolean,
    ) {
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
        if (clazz == null) {
            Diag.log("tips", "找不到 $className（$label）")
            return
        }

        val named = names.mapNotNull { name ->
            clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
        }
        val targets = if (named.isNotEmpty()) {
            named
        } else {
            clazz.declaredMethods.filter {
                it.parameterCount == 0 &&
                    it.returnType == Void.TYPE &&
                    Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers)
            }
        }

        if (targets.isEmpty()) {
            Diag.log("tips", "$className 没有可挂的方法（$label）")
            return
        }

        val hooked = mutableListOf<String>()
        for (method in targets) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (enabled()) null else chain.proceed()
                }
                hooked += method.name
            }
        }

        Diag.log(
            "tips",
            "$label：已挂钩 ${clazz.simpleName}.${hooked.joinToString("/")}" +
                if (named.isEmpty()) "（按名字没找到，退化为全屏蔽）" else "",
        )
    }

    // ---------------------------------------------------------------- 跳过返回值

    /**
     * 缓存「什么都不做」的代理。返回类型是接口时用它代替 null，
     * 这样调用方拿到的是一个合法对象，之后调 `dismiss()` 之类不会 NPE。
     */
    private val proxies = ConcurrentHashMap<Class<*>, Any>()

    private fun skipValue(returnType: Class<*>): Any? {
        if (returnType == Void.TYPE) return null
        if (!returnType.isInterface) return null

        proxies[returnType]?.let { return it }

        val proxy = runCatching {
            Proxy.newProxyInstance(
                returnType.classLoader ?: RestrictionGuard::class.java.classLoader,
                arrayOf(returnType),
                InvocationHandler { _, method, _ -> defaultValueOf(method.returnType) },
            )
        }.getOrNull() ?: return null

        proxies[returnType] = proxy
        return proxy
    }

    private fun defaultValueOf(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        else -> null
    }
}

package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    /** 发送状态指示的基类，图标（红叹号）唯一的显示点在这里。 */
    private const val STATUS_INDICATOR = "X.179c"

    private const val SAMPLE_LIMIT = 40

    /** 显示方法被拦下的次数。有它才能在日志里区分「没命中」和「命中但无效」。 */
    private val showHits = AtomicInteger(0)

    /** 登记过的发送状态图标个数。 */
    private val captured = AtomicInteger(0)

    /** 登记失败的次数，只报第一条，避免刷屏。 */
    private val captureMissed = AtomicInteger(0)

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

        // 聊天发送状态（红叹号 + 说明文字）：要同时处理基类、子类和控件本身，见函数注释。
        hookSendStatus(module, loader, rules)
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
                    if (!enabled()) return@intercept chain.proceed()

                    // 命中日志：没有它就没法区分「没挂上 / 挂上没命中 / 命中但无效」
                    if (showHits.incrementAndGet() <= SAMPLE_LIMIT) {
                        Diag.log("tips", "$label：拦下 ${clazz.simpleName}.${method.name}()")
                    }
                    null
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

    // ---------------------------------------------------------------- 聊天发送状态

    /**
     * 聊天发送状态指示（红叹号 + 说明文字）。
     *
     * 逆向抖音 40.2.0 的真实结构：
     *
     * ```
     * LX/179c （基类）
     *   b: ImageView                          // 状态图标（红叹号）
     *   LIZ()V    -> b.setImageResource + b.setContentDescription
     *                + b.setVisibility(VISIBLE)        ★ 图标唯一的显示点
     *   LIZLLL()V -> b.setVisibility(GONE)                // 隐藏
     *   LIZIZ/LIZJ -> throw NPE（留给子类实现的占位）
     *
     * StatusIconWithText extends LX/179c
     *   LIZ()V  -> invoke-super LIZ()（显示图标）+ 设置并显示说明文字   ★ 显示
     *   LJI()V  -> 只动说明文字，**完全不碰图标**
     *   LIZJ()V -> 把图标和文字都藏起来，再调 LJI()
     * ```
     *
     * 关键点：**图标只有基类 `LX/179c.LIZ()` 能显示**，`LJI()` 跟它无关。
     * 所以要三重保险，任何一条生效都能压住：
     *
     *  1. 拦子类的 `LIZ` / `LJI` —— 挡掉它的 `invoke-super` 和说明文字
     *  2. 拦基类的 `LIZ()` —— 挡住任何直接走基类显示图标的路
     *  3. **把这个 ImageView 登记进 [TextHider] 的压制表** ——
     *     它是聊天 cell 布局里就有的控件，**很可能在 XML 里默认就是 VISIBLE**。
     *     那种情况下拦「显示方法」等于什么都没做：它压根不需要被「显示」就已经亮着了。
     *     （这正是旧版按资源 id 压制那条路在干的活。改成按实例登记更精确，
     *     不会像 `0x7f0aa9d7` 那样把复用了同一个 id 的其它界面一起干掉。）
     *
     * 登记动作挂在「显示方法」和「构造方法」两处：前者一定能拿到实例，
     * 后者覆盖「布局默认 VISIBLE、显示方法从没被调用过」的情况。
     * 两条都是低频路径，开销可以忽略。
     *
     * 不动 `LIZJ` / `LIZLLL` 这些隐藏路径，避免和它自己的状态机打架。
     */
    private fun hookSendStatus(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val sub = runCatching { Class.forName(SEND_STATUS, false, loader) }.getOrNull()
        val base = runCatching { Class.forName(STATUS_INDICATOR, false, loader) }.getOrNull()

        // 图标字段在基类上（StatusIconWithText 继承它）
        val iconField = base?.let { runCatching { it.getField("b") }.getOrNull() }

        if (sub == null) {
            Diag.log("tips", "找不到 $SEND_STATUS（聊天发送状态）")
        } else {
            hookShowMethod(module, sub, "LIZ", "聊天发送状态", rules, iconField)
            hookShowMethod(module, sub, "LJI", "聊天发送状态", rules, iconField)
        }

        if (base == null) {
            Diag.log("tips", "找不到 $STATUS_INDICATOR（发送状态基类）")
            return
        }

        hookShowMethod(module, base, "LIZ", "发送状态图标", rules, iconField)
        hookIconCapture(module, base, iconField, rules)
    }

    /**
     * 拦掉一个「显示」方法，并顺手把它持有的图标控件登记进压制表。
     *
     * 登记与开关无关（代价只是一次反射读字段），这样用户把开关打开时立刻生效，
     * 不必等下一次「显示」被调用。
     */
    private fun hookShowMethod(
        module: XposedModule,
        clazz: Class<*>,
        name: String,
        label: String,
        rules: RuleSource,
        iconField: Field?,
    ) {
        val method = clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
        if (method == null) {
            Diag.log("tips", "$label：${clazz.simpleName} 里没有 $name()V")
            return
        }

        runCatching {
            module.hook(method).intercept { chain ->
                registerIcon(chain.thisObject, iconField, rules)

                if (!rules.hideTips()) return@intercept chain.proceed()

                if (showHits.incrementAndGet() <= SAMPLE_LIMIT) {
                    Diag.log("tips", "$label：拦下 ${clazz.simpleName}.$name()")
                }
                null
            }
            Diag.log("tips", "$label：已挂钩 ${clazz.simpleName}.$name()")
        }.onFailure { Diag.log("tips", "$label：${clazz.simpleName}.$name() 挂载失败: $it") }
    }

    /**
     * 在构造方法返回后拿到图标控件。覆盖「布局里默认 VISIBLE、
     * 显示方法从没被调用过」的情况——那时候只靠拦显示方法是压不住的。
     */
    private fun hookIconCapture(
        module: XposedModule,
        base: Class<*>,
        iconField: Field?,
        rules: RuleSource,
    ) {
        val ctor = base.declaredMethods.firstOrNull { it.name == "<init>" && it.parameterCount == 1 }
        if (ctor == null || iconField == null) {
            Diag.log("tips", "无法登记状态图标：构造方法或字段 b 没找到")
            return
        }

        runCatching {
            module.hook(ctor).intercept { chain ->
                val result = chain.proceed()
                registerIcon(chain.thisObject, iconField, rules)
                result
            }
            Diag.log("tips", "已挂钩 $STATUS_INDICATOR.<init>（构造时登记红叹号控件）")
        }.onFailure { Diag.log("tips", "$STATUS_INDICATOR.<init> 挂载失败: $it") }
    }

    /** 把实例持有的图标控件登记进压制表；首次登记时把它按成 GONE。 */
    private fun registerIcon(instance: Any?, iconField: Field?, rules: RuleSource) {
        if (instance == null || iconField == null) return

        val icon = runCatching { iconField.get(instance) as? View }.getOrNull()
        if (icon == null) {
            if (captureMissed.incrementAndGet() == 1) {
                Diag.log("tips", "登记状态图标失败：拿不到 ImageView（instance=$instance）")
            }
            return
        }

        if (!TextHider.markTip(icon)) return

        if (captured.incrementAndGet() <= SAMPLE_LIMIT) {
            Diag.log("tips", "已登记状态图标控件（后续任何显示请求都会被压成 GONE）")
        }
        if (rules.hideTips()) icon.visibility = View.GONE
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

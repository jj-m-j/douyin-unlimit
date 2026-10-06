package io.github.jj_m_j.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「隐藏限制提示」——把抖音宣告「你被限制了」的三种载体一起吞掉。
 *
 *   1. 弹窗吐司        DUX Toast 体系（DuxToastV2 / 旧版 DuxToast）+ 系统 Toast 兜底
 *   2. 消息页顶部横幅  ChatBanTipsLogic（整个类只服务这一条横幅）
 *   3. 聊天发送状态    StatusIconWithText（红叹号 + 说明文字）
 *
 * 三处在代码里是三套完全不同的实现，用户看到的却是同一件事，所以共用一个开关。
 *
 * ## 跨版本适配：只写死稳定的名字，其余按形状找
 *
 * 目标类里的**混淆名**（`LX/179c` 这种基类名、`LIZ`/`LJLLLLLL` 这种方法名、
 * `b`/`f` 这种字段名）在抖音换版本时**一定会变**。写死它们等于每升级一次就废一次，
 * 而且日志里只留一句「找不到」，很难查。所以这里改成：
 *
 * | 目标 | 怎么定位 | 为什么稳 |
 * |---|---|---|
 * | 发送状态基类 | `StatusIconWithText.superclass` | 不依赖基类叫什么 |
 * | 状态图标 / 说明文字 | 按字段**类型**（`ImageView` / `TextView`）找 | 不依赖字段叫什么 |
 * | 横幅的显示/隐藏方法 | 该类所有**无参 void 动作方法** | 不依赖方法叫什么 |
 * | 点赞回滚 | 收 `Exception` 的方法（见 LocalDigg） | 不依赖方法叫什么 |
 * | 吐司入口 | 参数含 `CharSequence` / `String` 的方法 | 不依赖方法叫什么 |
 *
 * 只写死的名字是抖音自己的**业务类名**（`FeedDiggPresenter`、`ChatBanTipsLogic`、
 * `StatusIconWithText`、`DuxToastV2`）—— 它们是 R8 保留下来的可读名，跨版本基本不变。
 *
 * ## 发送状态为什么要「按实例压制」，而不是拦显示方法
 *
 * 逆向 40.2.0 的结果（先说结论：**图标只有基类能显示，而且控件可能默认就是 VISIBLE**）：
 *
 * ```
 * LX/179c （基类）
 *   b: ImageView        LIZ()V    -> setImageResource + setVisibility(VISIBLE) ← 图标唯一显示点
 *                       LIZLLL()V -> setVisibility(GONE)
 * StatusIconWithText extends LX/179c
 *   f: DmtTextView      LIZ()V -> invoke-super LIZ() + 显示说明文字
 *                       LJI()V -> 只动文字，**完全不碰图标**
 * ```
 *
 * 于是「拦显示方法」有两个洞：拦子类拦不到基类那条路；更要命的是，如果这个
 * ImageView 在聊天 cell 布局里**默认就是 VISIBLE**，那它压根不需要被「显示」，
 * 拦显示方法等于什么都没做。
 *
 * 所以真正可靠的做法是**在组件构造时把它的控件抓出来登记**，之后任何
 * `setVisibility(VISIBLE)` 都被改写成 `GONE`（见 [TextHider]）。这条路线
 * 完全不需要知道任何方法名，天然跨版本。
 */
internal object RestrictionGuard {

    private val TOAST_CLASSES = listOf(
        "com.bytedance.dux.toast.DuxToastV2",
        "com.bytedance.dux.toast.DuxToast",
        "com.bytedance.dux.toast.DuxToastContent\$DuxToastTextContent",
    )

    private const val IM_BAN_TIPS_LOGIC =
        "com.ss.android.ugc.aweme.im.sdk.module.session.rips.sessionheader.tips.ChatBanTipsLogic"

    private const val SAMPLE_LIMIT = 40

    /** 拦下「显示」的次数。有它才能在日志里区分「没命中」和「命中但无效」。 */
    private val blockHits = AtomicInteger(0)

    /** 压住发送失败图标的次数。 */
    private val iconHits = AtomicInteger(0)

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookSystemToast(module, rules)
        for (name in TOAST_CLASSES) hookDuxToast(module, loader, name, rules)

        hookImBanTips(module, loader, rules)
        hookSendFailIcon(module, loader, rules)

        Diag.log("tips", "限制提示类 hook 安装完成")
    }

    // ---------------------------------------------------------------- 发送失败图标

    /**
     * 聊天里那条「发送失败」的红色叹号（真机视图树：
     * `com.ss.android.ugc.exview.ImImageView{... #7f0ab151 app:id/04_ ...}`）。
     *
     * ## 为什么不能只拦 `View.setVisibility`
     *
     * 因为这个类**自己重写了 `setVisibility`**：
     *
     * ```java
     * ImImageView.setVisibility(int v) {
     *     ImageView.setVisibility(v);        // 走的是 invoke-super
     *     setImageResource(...);             // 顺便把图重新设一遍
     * }
     * ImImageView.onAttachedToWindow() { ...; getVisibility(); setImageResource(...); }
     * ```
     *
     * 应用里拿着 `ImImageView` 引用调 `setVisibility(VISIBLE)` 时，虚分派落到**子类方法**，
     * 不会经过挂在 `View.setVisibility` 上的钩子。而且它继承链上的方法都很短，
     * 随时可能被 ART 内联（见 §「不要 hook 抖音自研的短方法」）。
     *
     * ## 所以用「反射构造」这个内联不了的入口
     *
     * 布局里的 View 是 `LayoutInflater` 通过 `constructor.newInstance()` **反射**造出来的，
     * 反射调用无法被内联 —— 所以它的构造方法一定会走到。在这里读一下 id，
     * 命中就登记进压制表并直接按掉，之后任何显示请求都会被压成 GONE。
     *
     * 同时额外挂一份**子类的** `setVisibility`，两条路一起堵。
     */
    private fun hookSendFailIcon(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = Targets.load(loader, Targets.SEND_FAIL_ICON_CLASS)
        if (clazz == null) {
            Diag.log("tips", "找不到 ${Targets.SEND_FAIL_ICON_CLASS}（发送失败图标）")
            return
        }

        val ctors = Targets.constructorsOf(clazz)
        var hookedCtors = 0
        for (ctor in ctors) {
            runCatching {
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    considerIcon(chain.thisObject, rules)
                    result
                }
                hookedCtors++
            }
        }
        Diag.log("tips", "发送失败图标：已挂钩 ${clazz.simpleName} 的 $hookedCtors/${ctors.size} 个构造方法")

        val setVisibility = clazz.declaredMethods.firstOrNull {
            it.name == "setVisibility" && it.parameterCount == 1
        }
        if (setVisibility == null) {
            Diag.log("tips", "发送失败图标：${clazz.simpleName} 没有自己重写 setVisibility")
            return
        }

        runCatching {
            module.hook(setVisibility).intercept { chain ->
                if (isSendFailIcon(chain.thisObject) && rules.hideTips()) {
                    reportIconHit()
                    alsoHideWrapper(chain.thisObject, rules)
                    return@intercept chain.proceed(arrayOf<Any?>(View.GONE))
                }
                chain.proceed()
            }
            Diag.log("tips", "发送失败图标：已挂钩 ${clazz.simpleName}.setVisibility(int)")
        }.onFailure { Diag.log("tips", "发送失败图标：setVisibility 挂载失败: $it") }
    }

    /**
     * 顺手把「只包着这一个图标」的容器也藏掉。
     *
     * 真机视图树里，图标外面套着一个 89x55 的 `FrameLayout`（图标本体是居中的 55x55）。
     * 只把图标 GONE 的话，那个容器还在：占着位置、而且还接得住点击
     * （点它会弹重发提示）。所以只在这个容器**恰好只有一个孩子**时才一起藏 ——
     * 判据足够窄，不会连带藏掉别的内容。
     */
    private fun alsoHideWrapper(instance: Any?, rules: RuleSource) {
        val view = instance as? View ?: return
        val wrapper = view.parent as? FrameLayout ?: return
        if (wrapper.childCount != 1) return
        if (!TextHider.markTip(wrapper)) return

        Diag.log("tips", "已登记发送失败图标的外层容器（只有一个孩子）")
        if (rules.hideTips()) wrapper.visibility = View.GONE
    }

    /** 构造完成时调用：是这个图标就登记进压制表，并立刻按掉。 */
    private fun considerIcon(instance: Any?, rules: RuleSource) {
        val view = instance as? View ?: return
        if (!isSendFailIcon(view)) return
        if (!TextHider.markTip(view)) return

        Diag.log("tips", "已登记发送失败图标 #0x%08x，后续显示请求都会被压成 GONE".format(view.id))
        if (rules.hideTips()) view.visibility = View.GONE
    }

    private fun isSendFailIcon(instance: Any?): Boolean {
        val view = instance as? View ?: return false
        return view.id == Targets.SEND_FAIL_ICON_ID
    }

    private fun reportIconHit() {
        if (iconHits.incrementAndGet() <= SAMPLE_LIMIT) {
            Diag.log("tips", "压住发送失败图标 #0x%08x".format(Targets.SEND_FAIL_ICON_ID))
        }
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
                // shouldBlockToast 内部已经按各自的开关判断（内置词 → 限制提示；用户词 → 关键词拦截）
                if (toast != null && rules.shouldBlockToast(textOf(toast))) {
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

    /**
     * DUX 的类名稳定，但**收口点不止一个**，而且方法名被混淆：
     *
     * ```
     * 系统吐司 LIZJ(DuxToastV2, Context, Drawable, CharSequence, ...)   静态，第 3 位是文案
     * 便捷入口 LJFF(Context, CharSequence)
     * 自绘入口 LJ(Context, boolean, String, Function1)
     * 上层封装 makeShowSystemToast$default / customToastShow$default / makeShowCustomToast$default
     * ```
     *
     * 逐个写死等于跟着抖音版本赛跑，所以按形状扫：**参数里含 `CharSequence` 或 `String`**。
     */
    private fun hookDuxToast(
        module: XposedModule,
        loader: ClassLoader,
        className: String,
        rules: RuleSource,
    ) {
        val clazz = Targets.load(loader, className)
        if (clazz == null) {
            Diag.log("tips", "找不到 $className")
            return
        }

        val hooked = mutableListOf<String>()
        for (method in clazz.declaredMethods) {
            if (!takesText(method) || !returnsVoidOrReference(method)) continue
            runCatching {
                module.hook(method).intercept { chain ->
                    for (arg in chain.args) {
                        if (arg is CharSequence && rules.shouldBlockToast(arg.toString())) {
                            Diag.log("tips", "拦下吐司：${arg.toString().take(40)}")
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
                "$className 里没有收 CharSequence 的入口（抖音版本可能变了）"
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

    // ---------------------------------------------------------------- 消息页横幅

    /**
     * `ChatBanTipsLogic` 整个类只服务这一条横幅，所以**不需要知道哪个方法负责显示** ——
     * 把这个类里所有「无参 void 动作方法」都拦掉即可（跳过生命周期回调）。
     *
     * 这是它跨版本的关键：40.2.0 里判定和显示分别是 `LJLLLLLL` / `LJJJLZIJ`，
     * 但下个版本会变成别的名字，而「无参 void」这个形状不会变。
     */
    private fun hookImBanTips(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = Targets.load(loader, IM_BAN_TIPS_LOGIC)
        if (clazz == null) {
            Diag.log("tips", "找不到 $IM_BAN_TIPS_LOGIC（消息页横幅）")
            return
        }

        val actions = Targets.zeroArgVoidActions(clazz)
        if (actions.isEmpty()) {
            Diag.log("tips", "${clazz.simpleName} 里没有无参 void 动作方法")
            return
        }

        val hooked = mutableListOf<String>()
        for (method in actions) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (!rules.hideTips()) return@intercept chain.proceed()
                    if (blockHits.incrementAndGet() <= SAMPLE_LIMIT) {
                        Diag.log("tips", "拦下消息页横幅动作 ${clazz.simpleName}.${method.name}()")
                    }
                    null
                }
                hooked += method.name
            }
        }

        Diag.log("tips", "消息页横幅：已挂钩 ${clazz.simpleName}.[${hooked.joinToString("/")}]")
    }

    // ---------------------------------------------------------------- 聊天发送状态
    //
    // 聊天里那条「发送失败」的红色叹号，**不在这里处理** —— 由 TextHider 的压制层
    // 按「类名 + 资源 id」拦掉（见 Targets.SEND_FAIL_ICON_ID 里的说明）。
    //
    // 曾经试过另一条路：在 StatusIconWithText 构造时按字段类型把控件抓出来登记。
    // 真机日志否掉了它 —— 那个类的构造方法和 LIZ/LJI 都只有十几个指令，
    // **会被 ART 内联**，hook 挂上去连「进入」都没有一次（libxposed 文档明确警告过
    // 「被 inline 的短方法 hook 不会触发」）。所以这条路线直接删掉，不做无用功。

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

package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
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

    /** 业务类名，R8 保留的可读名，跨版本基本不变。 */
    private const val SEND_STATUS =
        "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.status.sendstatus.StatusIconWithText"

    private const val SAMPLE_LIMIT = 40

    /** 捕获相关的诊断日志条数上限（含「进入」和失败）。 */
    private const val CAPTURE_LOG_LIMIT = 10

    /** 拦下「显示」的次数。有它才能在日志里区分「没命中」和「命中但无效」。 */
    private val blockHits = AtomicInteger(0)

    /** 登记过的控件个数。 */
    private val captured = AtomicInteger(0)

    /** 进入捕获钩子的次数。用它区分「钩子没触发」和「触发了但抓不到控件」。 */
    private val enterHits = AtomicInteger(0)

    /** 登记失败的次数，只报前几条，避免刷屏。 */
    private val captureMissed = AtomicInteger(0)

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookSystemToast(module, rules)
        for (name in TOAST_CLASSES) hookDuxToast(module, loader, name, rules)

        hookImBanTips(module, loader, rules)
        hookSendStatus(module, loader, rules)

        Diag.log("tips", "限制提示类 hook 安装完成")
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
                    if (!rules.hideTips()) return@intercept chain.proceed()
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

    /**
     * 结构和设计理由见类注释。要点：
     *
     *  - 基类名（`LX/179c`）**不写死**，用 `superclass` 拿
     *  - 图标 / 文字字段名（`b` / `f`）**不写死**，按类型（`ImageView` / `TextView`）找
     *  - 不拦任何显示方法：改成**构造时把控件登记进压制表**，之后 VISIBLE 一律变 GONE
     *
     * 最后一条尤其重要：它同时覆盖了「基类那条显示路径」和「控件在布局里默认就 VISIBLE」
     * 这两个之前都漏掉的情况，而且完全不需要方法名。
     */
    private fun hookSendStatus(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val sub = Targets.load(loader, SEND_STATUS)
        if (sub == null) {
            Diag.log("tips", "找不到 $SEND_STATUS（聊天发送状态）")
            return
        }

        val base = sub.superclass
        Diag.log("tips", "聊天发送状态：${sub.simpleName} extends ${base?.simpleName}")

        // 布局传进来的两个控件。字段名会被混淆，但类型不会。
        val fields = Targets.fieldsOfType(sub, ImageView::class.java) +
            Targets.fieldsOfType(sub, TextView::class.java)
        if (fields.isEmpty()) {
            Diag.log("tips", "聊天发送状态：没找到 ImageView/TextView 字段，压不住发送状态")
        } else {
            Diag.log(
                "tips",
                "聊天发送状态：按类型找到 ${fields.size} 个控件字段 " +
                    fields.joinToString("/") { "${it.type.simpleName}:${it.name}" },
            )
        }

        // 1) 构造时登记（最全：这时所有字段都已赋值）
        for (ctor in Targets.constructorsOf(sub)) {
            hookCapture(module, ctor, fields, rules, "构造")
        }

        // 2) 动作方法里也登记一次。万一某个版本连构造方法都挂不上，这里能兜住 ——
        //    这些方法在每次绑定/重绑时都会走，登记一次就永久生效。
        for (method in Targets.zeroArgVoidActions(sub)) {
            hookCapture(module, method, fields, rules, method.name)
        }
    }

    /** 挂一个「进入时把控件登记进压制表」的钩子。 */
    private fun hookCapture(
        module: XposedModule,
        target: Executable,
        fields: List<Field>,
        rules: RuleSource,
        label: String,
    ) {
        if (fields.isEmpty()) return

        val where = "${target.declaringClass.simpleName}.$label" +
            "(${target.parameterTypes.joinToString(",") { it.simpleName }})"

        runCatching {
            module.hook(target).intercept { chain ->
                val result = chain.proceed()

                // 采样记一行「钩子进来了」。没有它就没法区分
                // 「这个方法压根没被调用」和「调用了但里面抓不到控件」——
                // 上一轮就是因为只有后半段的日志，才看不出是哪种。
                if (enterHits.incrementAndGet() <= CAPTURE_LOG_LIMIT) {
                    Diag.log("tips", "聊天发送状态：进入 $where")
                }
                captureAll(chain.thisObject, fields, rules, where)
                result
            }
            Diag.log("tips", "聊天发送状态：已挂钩 $where")
        }.onFailure { Diag.log("tips", "聊天发送状态：$where 挂载失败: $it") }
    }

    private fun captureAll(instance: Any?, fields: List<Field>, rules: RuleSource, where: String) {
        if (instance == null) {
            reportOnce("$where：thisObject 为 null，抓不到控件")
            return
        }

        for (field in fields) {
            val value = runCatching { field.get(instance) }.getOrNull()
            val view = value as? View
            if (view == null) {
                reportOnce("$where：字段 ${field.name} 读不到 View（值=$value）")
                continue
            }
            if (!TextHider.markTip(view)) continue

            if (captured.incrementAndGet() <= SAMPLE_LIMIT) {
                Diag.log(
                    "tips",
                    "已登记 ${view.javaClass.simpleName}（${field.name}）—— 显示请求会被压成 GONE",
                )
            }
            // 布局里默认 VISIBLE 的情况：登记的同时直接按掉
            if (rules.hideTips()) view.visibility = View.GONE
        }
    }

    private fun reportOnce(message: String) {
        if (captureMissed.incrementAndGet() <= CAPTURE_LOG_LIMIT) {
            Diag.log("tips", "登记失败：$message")
        }
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

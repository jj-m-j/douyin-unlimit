package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 完全由模块实现的本地点赞：不经过抖音任何接口。
 *
 * ## 走过的弯路（都记下来，避免以后再踩）
 *
 * 1. **网络层**：`OkHttpClient.newCall` 和 `SsHttpCall.enqueue` 都成功挂上了，真机实测
 *    整轮使用**零请求经过它们**——抖音 API 走 TTNet（Cronet 原生栈），Java 侧拦不到。
 * 2. **`DiggAnimationView.onTouchEvent`**：挂上了但从未被调用。那是点赞*动画*的载体，
 *    不是能点的按钮。
 * 3. **`View.performClick` + 找 `VideoDiggView` 祖先**：也从未命中。
 *    原因：`VideoDiggView extends AsyncBaseVideoItemView`，**它本身不是 View**，
 *    而是包着 View 的控制器，所以它不可能出现在任何视图的祖先链里。
 *
 * ## 现在挂在哪
 *
 * 点睛之笔是不写死任何混淆名，全部运行时推导：
 *
 *   1. 从 `VideoDiggView` 的字段里，找出那个类型实现了 `View.OnClickListener` 的字段
 *      （smali 里是 `K:LY/ACListenerS197S0100000_38;`），拿到它的**运行时类型**
 *   2. 挂这个类型所有 `onClick*` 方法
 *   3. 每次触发时判断：这个 lambda 捕获的对象是不是 `VideoDiggView` 实例
 *      （R8 的 lambda 类会把捕获对象存在 `l0` 这类字段里）
 *      —— 是，就说明用户点的是点赞
 *   4. 由模块直接改图标选中态 + 点赞数，然后**不调用原方法**（吞掉）
 *      → 抖音的点赞逻辑不执行 → 请求不发 → 服务端无从驳回 → 没有回滚
 *
 * 这样即使抖音改名（`ACListenerS...` 后面那串数字、`onClick$56` 的编号、
 * `DiggAnimationView` 字段名）也照样能工作。
 *
 * ## 已知限制
 *
 * 抖音的数据模型仍是「未点赞」，视图被 RecyclerView 回收重绑后图标会还原
 * —— 滑走再滑回来，本地点赞会丢。
 */
internal object LocalDigg {

    private const val DIGG_WIDGET = "com.ss.android.ugc.aweme.feed.ui.VideoDiggView"
    private const val DIGG_ICON = "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView"
    private const val TEXT_VIEW = "android.widget.TextView"
    private const val ON_CLICK_LISTENER = "android.view.View\$OnClickListener"

    private const val SAMPLE_LIMIT = 40
    private var sampleCount = 0

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val widgetClass = runCatching { Class.forName(DIGG_WIDGET, false, loader) }.getOrNull()
        if (widgetClass == null) {
            Diag.log("digg", "找不到 $DIGG_WIDGET")
            return
        }

        val listenerInterface = runCatching { Class.forName(ON_CLICK_LISTENER, false, loader) }.getOrNull()
        if (listenerInterface == null) {
            Diag.log("digg", "找不到 $ON_CLICK_LISTENER")
            return
        }

        // 运行时推导：点击监听器到底被混淆成了哪个类
        val listenerClass = widgetClass.declaredFields
            .map { it.type }
            .firstOrNull { listenerInterface.isAssignableFrom(it) && it != listenerInterface }

        if (listenerClass == null) {
            Diag.log("digg", "$DIGG_WIDGET 里没有找到 OnClickListener 类型的字段")
            return
        }

        val targets = listenerClass.declaredMethods.filter { it.name.startsWith("onClick") }
        if (targets.isEmpty()) {
            Diag.log("digg", "${listenerClass.name} 里没有 onClick 方法")
            return
        }

        var hooked = 0
        for (method in targets) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (!rules.blockDiggUpload()) return@intercept chain.proceed()

                    val lambda = chain.thisObject ?: chain.args.firstOrNull()
                    val captured = capturedObject(lambda)
                    if (captured != null && captured.javaClass === widgetClass) {
                        applyLocalLike(captured)
                        // 吞掉：抖音自己的点赞逻辑不执行，请求不会发出
                        return@intercept null
                    }

                    chain.proceed()
                }
                hooked++
            }
        }

        Diag.log(
            "digg",
            if (hooked == 0) {
                "点赞监听器方法一个都没挂上（${listenerClass.name}）"
            } else {
                "已挂钩点赞监听器 ${listenerClass.name} 的 $hooked 个 onClick* 方法（拦截为纯本地）"
            },
        )
    }

    /** R8 的 lambda 类会把捕获对象存在 `l0` 这类字段里；取第一个非静态引用字段。 */
    private fun capturedObject(lambda: Any?): Any? {
        if (lambda == null) return null
        return runCatching {
            val field = lambda.javaClass.declaredFields.firstOrNull {
                !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive
            } ?: return null
            field.isAccessible = true
            field.get(lambda)
        }.getOrNull()
    }

    // ---- VideoDiggView 的字段：按类型取，混淆改名也不影响 ----

    @Volatile
    private var iconField: Field? = null

    @Volatile
    private var countField: Field? = null

    @Volatile
    private var fieldsResolved = false

    private fun resolveFields(widget: Any) {
        if (fieldsResolved) return
        fieldsResolved = true
        val cls = widget.javaClass
        iconField = cls.declaredFields
            .firstOrNull { it.type.name == DIGG_ICON }
            ?.also { runCatching { it.isAccessible = true } }
        countField = cls.declaredFields
            .firstOrNull { it.type.name == TEXT_VIEW }
            ?.also { runCatching { it.isAccessible = true } }
        Diag.log(
            "digg",
            "VideoDiggView 字段定位：图标=${iconField?.name ?: "未找到"}，点赞数=${countField?.name ?: "未找到"}",
        )
    }

    private fun applyLocalLike(widget: Any) {
        resolveFields(widget)

        val icon = runCatching { iconField?.get(widget) as? View }.getOrNull()
        if (icon == null) {
            Diag.log("digg", "拿不到点赞图标")
            return
        }

        val liked = !icon.isSelected
        icon.isSelected = liked
        icon.refreshDrawableState()

        val count = runCatching { countField?.get(widget) as? TextView }.getOrNull()
        val before = count?.text?.toString()
        if (count != null) {
            count.text = bumpCount(before, if (liked) 1 else -1)
        }

        if (sampleCount++ < SAMPLE_LIMIT) {
            Diag.log("digg", "本地点赞 liked=$liked，点赞数 $before -> ${count?.text ?: "控件未定位"}")
        }
    }

    /** 支持 1.2万 / 1.2亿 这类写法。解析不出来就原样返回，不乱改。 */
    private fun bumpCount(text: CharSequence?, delta: Int): String {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return if (delta > 0) "1" else "0"

        val unit = when {
            raw.endsWith("万") -> 10_000.0
            raw.endsWith("亿") -> 100_000_000.0
            else -> 1.0
        }
        val numberPart = if (unit == 1.0) raw else raw.dropLast(1)
        val value = numberPart.toDoubleOrNull() ?: return raw

        val total = (value * unit + delta).coerceAtLeast(0.0)

        return when {
            total >= 100_000_000 -> trimZero(total / 100_000_000) + "亿"
            total >= 10_000 -> trimZero(total / 10_000) + "万"
            else -> total.toLong().toString()
        }
    }

    private fun trimZero(value: Double): String =
        (Math.round(value * 10.0) / 10.0).toString().removeSuffix(".0")
}

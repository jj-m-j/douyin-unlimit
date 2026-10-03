package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewParent
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * 完全由模块实现的本地点赞：不经过抖音任何接口。
 *
 * ## 为什么不在网络层拦
 *
 * 真机日志已证明：`OkHttpClient.newCall` 和 `SsHttpCall.enqueue` 都成功挂上了，
 * 但整轮使用中**零请求经过它们**——抖音的 API 走 TTNet（Cronet 原生栈），
 * 请求在 native 层构建，Java 侧既拦不到也没有 URL。这条路是封死的。
 *
 * ## 为什么挂在 View.performClick
 *
 * 上一版挂在 `DiggAnimationView.onTouchEvent` 上，实测**从未被调用**——
 * 那个类是点赞动画的载体，真正被点击的是外面包着它的容器。
 *
 * 逆向确认点赞按钮的实现：
 *
 *   com.ss.android.ugc.aweme.feed.ui.VideoDiggView
 *     s : DiggAnimationView     // 图标
 *     t : TextView              // 点赞数
 *     K : ACListenerS...        // 点击监听器
 *
 *     LJJIIJ(Aweme, Map, Z, Z)  // "update_diig_view" -> ImageView.setSelected(...)  ← 变红
 *     LJJIFFI(J, Aweme, Z, Z)   // "digg_count_state" -> TextView.setText(...)      ← 数字
 *
 * `View.performClick()` 是框架方法、名字永不被混淆，而且所有点击都会经过它。
 * 从被点击的视图往上找 `VideoDiggView` 祖先，命中就说明用户点的是点赞：
 *
 *   1. 自己改图标选中态 + 点赞数，**然后 return true 吞掉事件**
 *   2. 抖音的监听器不会执行 -> 请求不会发出 -> 服务端无从驳回 -> 没有回滚
 *
 * 字段不按名字取、按**类型**取（DiggAnimationView / TextView），这样即使
 * 混淆后的字段名变了也照样能找到。
 *
 * ## 已知限制
 *
 * 抖音的数据模型仍是「未点赞」，所以视图被 RecyclerView 回收重绑后，
 * 抖音会按模型把图标刷回未点赞——滑走再滑回来，本地点赞会丢。
 */
internal object LocalDigg {

    private const val DIGG_WIDGET = "com.ss.android.ugc.aweme.feed.ui.VideoDiggView"
    private const val DIGG_ICON = "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView"
    private const val TEXT_VIEW = "android.widget.TextView"

    private const val MAX_UP_LEVELS = 16

    // ---- 反射句柄，首次命中时解析一次 ----
    @Volatile
    private var widgetClass: Class<*>? = null

    @Volatile
    private var resolved = false

    private var iconField: Field? = null
    private var countField: Field? = null

    private const val SAMPLE_LIMIT = 40
    private var sampleCount = 0

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        widgetClass = runCatching { Class.forName(DIGG_WIDGET, false, loader) }.getOrNull()
        if (widgetClass == null) {
            Diag.log("digg", "找不到 $DIGG_WIDGET")
            return
        }

        val performClick = runCatching { View::class.java.getDeclaredMethod("performClick") }.getOrNull()
        if (performClick == null) {
            Diag.log("digg", "找不到 View.performClick")
            return
        }

        runCatching {
            module.hook(performClick).intercept { chain ->
                val view = chain.thisObject as? View ?: return@intercept chain.proceed()

                if (rules.blockDiggUpload()) {
                    val widget = findDiggWidget(view)
                    if (widget != null) {
                        applyLocalLike(widget)
                        // 吞掉：抖音的点击监听器不执行，点赞请求不会发出
                        return@intercept true
                    }
                }

                chain.proceed()
            }
            Diag.log("digg", "已挂钩 View.performClick（识别 $DIGG_WIDGET 并拦截为纯本地）")
        }.onFailure {
            Diag.log("digg", "LocalDigg 挂载失败: $it")
        }
    }

    /** 从被点击的视图往上找点赞容器。 */
    private fun findDiggWidget(from: View): Any? {
        val target = widgetClass ?: return null
        var parent: ViewParent? = from.parent
        var level = 0
        while (parent is View && level < MAX_UP_LEVELS) {
            if (parent.javaClass === target) return parent
            parent = parent.parent
            level++
        }
        return null
    }

    private fun resolveFields(widget: Any) {
        if (resolved) return
        resolved = true
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
            Diag.log("digg", "拿不到点赞图标字段")
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
            Diag.log(
                "digg",
                "本地点赞 liked=$liked，点赞数 $before -> ${count?.text ?: "控件未定位"}",
            )
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

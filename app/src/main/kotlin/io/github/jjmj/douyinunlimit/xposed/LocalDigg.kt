package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 完全由模块实现的本地点赞：不经过抖音任何接口，涵盖两个入口。
 *
 * ## 入口一：点击右侧的点赞图标
 *
 * 靠「点击探针」在真机上抓出来的（证据链完整）：
 *
 *   [click*] 点击 android.widget.FrameLayout #0x7f0a309c
 *            ← LinearLayout(0x7f0a30b1) < HPFrameLayout(0x7f0a30a4)
 *            < FeedRightScaleView(0x7f0a9ef5) < ...
 *
 *   FeedRightScaleView 就是右侧那排，两个 id 紧挨：
 *   LinearLayout[ FrameLayout(309c)=图标 , TextView(309d)=数字 ]
 *
 * 挂 `View.performClick()`，命中 `#0x7f0a309c` 即点赞。
 *
 * ## 入口二：双击屏幕点赞
 *
 * `VideoDiggView` 里有 `handle_double_click` / `click_double_like` 这两个事件键，
 * 说明双击是走 DataCenter 事件派发的。所以挂 `VideoDiggView` 上所有收 `KVData`
 * 的方法，读出 key，等于 `handle_double_click` 就本地化。
 *
 * ## 走过的弯路（记下来避免重蹈）
 *
 * 1. **网络层**：`OkHttpClient.newCall`、`SsHttpCall.enqueue` 都挂上了，真机实测
 *    零请求经过——抖音 API 走 TTNet（Cronet 原生栈），Java 侧拦不到。
 * 2. **`DiggAnimationView.onTouchEvent`**：挂上了但从未被调用，那是动画载体。
 * 3. **找 `VideoDiggView` 祖先**：从未命中，因为它 `extends AsyncBaseVideoItemView`，
 *    **本身不是 View**，不可能出现在视图祖先链里。
 * 4. **挂 `VideoDiggView` 的点击监听器**：228 个 onClick* 全挂上也没命中。
 *
 * ## 已知限制
 *
 * 抖音的数据模型仍是「未点赞」，视图回收重绑后图标会被刷回去
 * —— 滑走再滑回来，本地点赞会丢。
 */
internal object LocalDigg {

    /** 点赞按钮（FeedRightScaleView 里包着点赞图标的 FrameLayout）。 */
    private const val LIKE_BUTTON_ID = 0x7f0a309c

    private const val DIGG_WIDGET = "com.ss.android.ugc.aweme.feed.ui.VideoDiggView"
    private const val DIGG_ICON_CLASS = "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView"

    private const val DOUBLE_CLICK_KEY = "handle_double_click"

    private const val SAMPLE_LIMIT = 60
    private var sampleCount = 0

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        installClickEntry(module, rules)
        installDoubleTapEntry(module, loader, rules)
    }

    // ---------------------------------------------------------------- 入口一：点击图标

    private fun installClickEntry(module: XposedModule, rules: RuleSource) {
        val performClick = runCatching {
            View::class.java.getDeclaredMethod("performClick")
        }.getOrNull()
        if (performClick == null) {
            Diag.log("digg", "找不到 View.performClick")
            return
        }

        runCatching {
            module.hook(performClick).intercept { chain ->
                val view = chain.thisObject as? View ?: return@intercept chain.proceed()

                if (rules.blockDiggUpload() && view.id == LIKE_BUTTON_ID) {
                    val icon = findIconInButton(view)
                    val count = findSiblingCount(view)
                    applyLocalLike(icon, count)
                    // 吞掉：抖音的点赞逻辑不执行，请求不会发出
                    return@intercept true
                }

                chain.proceed()
            }
            Diag.log("digg", "入口一就绪：View.performClick 命中 #0x${LIKE_BUTTON_ID.toString(16)}")
        }.onFailure {
            Diag.log("digg", "入口一挂载失败: $it")
        }
    }

    /** 按钮内部找点赞图标：优先 DiggAnimationView，退化到第一个 ImageView。 */
    private fun findIconInButton(button: View): ImageView? {
        findDiggAnimationView(button)?.let { return it }
        var fallback: ImageView? = null
        fun walk(node: View) {
            if (fallback == null && node is ImageView) fallback = node
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) walk(node.getChildAt(i))
            }
        }
        walk(button)
        return fallback ?: (button as? ImageView)
    }

    private fun findDiggAnimationView(root: View): ImageView? {
        if (root.javaClass.name == DIGG_ICON_CLASS) return root as? ImageView
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findDiggAnimationView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findSiblingCount(button: View): TextView? {
        val parent = button.parent as? View ?: return null
        for (i in 0 until (parent as? ViewGroup)?.childCount.orZero()) {
            val child = (parent as ViewGroup).getChildAt(i)
            if (child !== button && child is TextView) return child
        }
        return null
    }

    private fun Int?.orZero(): Int = this ?: 0

    // ---------------------------------------------------------------- 入口二：双击屏幕

    private fun installDoubleTapEntry(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val widget = runCatching { Class.forName(DIGG_WIDGET, false, loader) }.getOrNull()
        if (widget == null) {
            Diag.log("digg", "入口二：找不到 $DIGG_WIDGET")
            return
        }

        // 收 KVData 的方法有若干个，逐个挂上、按事件 key 过滤
        val targets = widget.declaredMethods.filter {
            it.parameterCount == 1 && it.parameterTypes[0].name.contains("KVData")
        }
        if (targets.isEmpty()) {
            Diag.log("digg", "入口二：$DIGG_WIDGET 里没有收 KVData 的方法")
            return
        }

        var hooked = 0
        for (method in targets) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (!rules.blockDiggUpload()) return@intercept chain.proceed()

                    val event = chain.args.firstOrNull()
                    val key = readEventKey(event)
                    if (key == DOUBLE_CLICK_KEY) {
                        val widgetInstance = chain.thisObject
                        if (widgetInstance != null) {
                            applyLocalLikeFromWidget(widgetInstance)
                            Diag.debug("digg", "双击点赞已本地化（事件 $key）")
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
                hooked++
            }
        }

        Diag.log("digg", "入口二就绪：$DIGG_WIDGET 的 $hooked 个 KVData 方法（按 $DOUBLE_CLICK_KEY 过滤）")
    }

    /** KVData.getKey() 没被混淆，直接反射读。 */
    private fun readEventKey(event: Any?): String? = runCatching {
        event?.javaClass?.getMethod("getKey")?.invoke(event) as? String
    }.getOrNull()

    /** 双击入口拿到的就是 VideoDiggView 本身，按类型取它的图标 / 点赞数字段。 */
    private fun applyLocalLikeFromWidget(widget: Any) {
        val cls = widget.javaClass
        if (widgetFieldsClass !== cls) {
            widgetFieldsClass = cls
            iconField = cls.declaredFields.firstOrNull { it.type.name == DIGG_ICON_CLASS }
                ?.also { runCatching { it.isAccessible = true } }
            countField = cls.declaredFields.firstOrNull { it.type.name == android.widget.TextView::class.java.name }
                ?.also { runCatching { it.isAccessible = true } }
            Diag.log(
                "digg",
                "VideoDiggView 字段：图标=${iconField?.name ?: "未找到"}，点赞数=${countField?.name ?: "未找到"}",
            )
        }
        val icon = runCatching { iconField?.get(widget) as? ImageView }.getOrNull()
        val count = runCatching { countField?.get(widget) as? TextView }.getOrNull()
        applyLocalLike(icon, count)
    }

    private var widgetFieldsClass: Class<*>? = null
    private var iconField: Field? = null
    private var countField: Field? = null

    // ---------------------------------------------------------------- 共用

    private fun applyLocalLike(icon: ImageView?, count: TextView?) {
        if (icon == null) {
            Diag.log("digg", "没找到点赞图标")
            return
        }

        val liked = !icon.isSelected
        icon.isSelected = liked
        icon.refreshDrawableState()
        if (liked) playLikeAnimation(icon)

        val before = count?.text?.toString()
        if (count != null) {
            count.text = bumpCount(before, if (liked) 1 else -1)
        }

        if (sampleCount++ < SAMPLE_LIMIT) {
            Diag.log("digg", "本地点赞 liked=$liked，点赞数 $before -> ${count?.text ?: "未找到"}")
        }
    }

    // ---------------------------------------------------------------- 点赞动画

    private var animationEntry: Method? = null

    private var animationEntryResolved = false

    /**
     * 优先用抖音自己的点赞动画入口（`DiggAnimationView` 上
     * `(DiggAnimationView, boolean) -> void` 那个方法），找不到就用自己的缩放回弹兜底。
     */
    private fun playLikeAnimation(icon: ImageView) {
        if (icon.javaClass.name == DIGG_ICON_CLASS) {
            if (!animationEntryResolved) {
                animationEntryResolved = true
                animationEntry = icon.javaClass.declaredMethods.firstOrNull {
                    it.returnType == Void.TYPE &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == icon.javaClass &&
                        it.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }?.also { runCatching { it.isAccessible = true } }
                Diag.log("digg", "抖音点赞动画入口=${animationEntry?.name ?: "未找到，用内置缩放动画"}")
            }

            val played = runCatching {
                val entry = animationEntry ?: return@runCatching false
                if (Modifier.isStatic(entry.modifiers)) {
                    entry.invoke(null, icon, true)
                } else {
                    entry.invoke(icon, icon, true)
                }
                true
            }.getOrDefault(false)

            if (played) return
        }
        bounce(icon)
    }

    /** 兜底动画：放大再弹回。 */
    private fun bounce(icon: View) {
        runCatching {
            icon.animate()
                .scaleX(1.25f).scaleY(1.25f)
                .setDuration(90)
                .withEndAction {
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                .start()
        }
    }

    // ---------------------------------------------------------------- 数字

    /**
     * 点赞数 +1。
     *
     * **保持原格式**——原来是什么写法就按什么写法输出：
     * 纯数字（371283）就还是纯数字，`1.2万` 就还是万。
     * 早期版本无条件往「万/亿」上凑，把 371283 显示成了「37.1万」，与抖音自身的显示不一致。
     */
    private fun bumpCount(text: CharSequence?, delta: Int): String {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return if (delta > 0) "1" else "0"

        val suffix = when {
            raw.endsWith("万") -> "万"
            raw.endsWith("亿") -> "亿"
            else -> ""
        }
        val numberPart = if (suffix.isEmpty()) raw else raw.dropLast(1)
        val value = numberPart.toDoubleOrNull() ?: return raw

        return when (suffix) {
            "万" -> scaled(value + delta / 10_000.0) + "万"
            "亿" -> scaled(value + delta / 100_000_000.0) + "亿"
            else -> (value + delta).coerceAtLeast(0.0).toLong().toString()
        }
    }

    private fun scaled(value: Double): String =
        (Math.round(value * 10.0) / 10.0).toString().removeSuffix(".0")
}

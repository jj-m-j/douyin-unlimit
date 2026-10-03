package io.github.jjmj.douyinunlimit.xposed

import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import io.github.libxposed.api.XposedModule

/**
 * 完全由模块实现的本地点赞：不经过抖音任何接口。
 *
 * ## 为什么不用「拦 HTTP 请求」那条路
 *
 * 实测结论（真机日志）：`OkHttpClient.newCall` 与 `SsHttpCall.enqueue` 都成功挂上了，
 * 但整轮使用中**一条请求都没经过它们**——抖音的 API 走的是 TTNet（Cronet 原生栈），
 * 请求在 native 层构建，Java 侧拦不到、也没有可用的 URL。
 *
 * 所以改成在**输入层**截断：吞掉点赞图标的点击事件。
 *
 *   DiggAnimationView.onTouchEvent(MotionEvent)
 *       ACTION_DOWN -> 交给 super（保留按下态）
 *       ACTION_UP   -> 判断手指是否还在控件内；在 -> 由模块实现点赞，并返回 true 吞掉事件
 *
 * 返回 true 意味着**根本不调用 super**，抖音自己的 OnClickListener 不会被触发，
 * 于是连请求都不会发出——这是最彻底的「不走抖音接口」。
 *
 * ## 已知限制
 *
 * 抖音不知道我们点赞了，所以它的数据模型仍是「未点赞」。
 * 当这条视频的视图被 RecyclerView 回收、重新绑定回来时，抖音会按模型把图标刷回未点赞。
 * 也就是说：**滑走再滑回来，本地点赞会丢**。
 *
 * 要做到跨重绑保持，需要按视频 id（aid）记录状态并在绑定时重新套用，
 * 那需要另外定位「视图 ↔ aid」的映射关系，暂未实现。
 */
internal object LocalDigg {

    private const val DIGG_VIEW = "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView"

    /**
     * 点赞数控件 id。来自真机 Layout Inspect：
     * com.bytedance.dux.text.DuxTextView #7f0a309d
     *
     * 注意：资源 id 是 aapt 打包时分配的，抖音升级后可能变化。
     */
    private const val COUNT_VIEW_ID = 0x7f0a309d

    /** 向上找多少层父容器去定位点赞数。 */
    private const val MAX_UP_LEVELS = 12

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(DIGG_VIEW, false, loader) }.getOrNull()
        if (clazz == null) {
            Diag.log("digg", "找不到 $DIGG_VIEW")
            return
        }

        val onTouch = clazz.declaredMethods.firstOrNull { it.name == "onTouchEvent" }
        if (onTouch == null) {
            Diag.log("digg", "$DIGG_VIEW 没有 onTouchEvent")
            return
        }

        runCatching {
            module.hook(onTouch).intercept { chain ->
                if (!rules.blockDiggUpload()) return@intercept chain.proceed()

                val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                val event = chain.args.getOrNull(0) as? MotionEvent ?: return@intercept chain.proceed()

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> chain.proceed()

                    MotionEvent.ACTION_UP -> {
                        // super 会在手指移出时清掉按下态，据此判断这是一次有效点击
                        val tapped = view.isPressed
                        view.isPressed = false
                        if (tapped) applyLocalLike(view)
                        // 返回 true 吞掉事件：抖音的 OnClickListener 不会被触发，请求不会发出
                        true
                    }

                    else -> chain.proceed()
                }
            }
            Diag.log("digg", "已挂钩 $DIGG_VIEW.onTouchEvent（拦截点赞为纯本地）")
        }.onFailure {
            Diag.log("digg", "LocalDigg 挂载失败: $it")
        }
    }

    private fun applyLocalLike(view: View) {
        val liked = !view.isSelected
        view.isSelected = liked
        view.refreshDrawableState()

        val count = findCountView(view)
        if (count == null) {
            Diag.log("digg", "没找到点赞数控件 id=0x${COUNT_VIEW_ID.toString(16)}")
            return
        }

        val before = count.text?.toString()
        count.text = bumpCount(before, if (liked) 1 else -1)
        Diag.log("digg", "本地点赞 liked=$liked，点赞数 $before -> ${count.text}")
    }

    /** 从点赞图标往上找，定位同一项里的点赞数控件。 */
    private fun findCountView(from: View): TextView? {
        var parent: ViewParent? = from.parent
        var level = 0
        while (parent is View && level < MAX_UP_LEVELS) {
            val found = parent.findViewById<View>(COUNT_VIEW_ID)
            if (found is TextView) return found
            parent = parent.parent
            level++
        }
        return null
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

    private fun trimZero(value: Double): String {
        val oneDecimal = (Math.round(value * 10.0) / 10.0).toString()
        return oneDecimal.removeSuffix(".0")
    }
}

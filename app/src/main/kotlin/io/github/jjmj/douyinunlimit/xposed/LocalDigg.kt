package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedModule

/**
 * 完全由模块实现的本地点赞：不经过抖音任何接口。
 *
 * ## 按钮是怎么定位到的
 *
 * 靠「点击探针」（详细调试日志）在真机上抓出来的，证据链完整：
 *
 *   [click*] 点击 android.widget.FrameLayout #0x7f0a309c
 *            ← LinearLayout(0x7f0a30b1) < HPFrameLayout(0x7f0a30a4)
 *            < FeedRightScaleView(0x7f0a9ef5) < ...          ← 右侧操作栏
 *
 *   - 位于 FeedRightScaleView（抖音右侧那排 头像/点赞/评论/分享）
 *   - 父容器 LinearLayout(0x7f0a30b1)，兄弟节点是 DuxTextView(0x7f0a309d) = 点赞数
 *   - 两个 id 紧挨（309c / 309d），同一布局块分配
 *   => LinearLayout[ FrameLayout(309c)=图标 , TextView(309d)=数字 ]
 *
 * ## 走过的弯路（都记下来，避免重蹈）
 *
 * 1. **网络层**：`OkHttpClient.newCall`、`SsHttpCall.enqueue` 都挂上了，真机实测
 *    零请求经过——抖音 API 走 TTNet（Cronet 原生栈），Java 侧拦不到。
 * 2. **`DiggAnimationView.onTouchEvent`**：挂上了但从未被调用。那是点赞*动画*载体，
 *    不是按钮。
 * 3. **`View.performClick` + 找 `VideoDiggView` 祖先**：从未命中。因为
 *    `VideoDiggView extends AsyncBaseVideoItemView`——**它本身不是 View**，
 *    而是包着 View 的控制器，不可能出现在视图祖先链里。
 * 4. **`VideoDiggView` 的点击监听器**：挂上了 228 个 onClick* 方法也没命中，
 *    按钮的监听器不在那个 lambda 类里。
 *
 * ## 现在的做法
 *
 * `View.performClick()` 是所有点击的必经之路（探针已实证点赞走它），
 * 命中 `#0x7f0a309c` 就是点赞：
 *
 *   1. 找到图标（按钮内部的 DiggAnimationView，退化为第一个 ImageView）并置选中态
 *   2. 找到兄弟节点的点赞数 TextView 并 +1
 *   3. **返回 true 吞掉事件** → 抖音的点赞逻辑不执行 → 请求不发 → 服务端无从驳回
 *
 * ## 已知限制
 *
 * 抖音的数据模型仍是「未点赞」，视图被 RecyclerView 回收重绑后图标会被刷回去
 * —— 滑走再滑回来，本地点赞会丢。
 * 资源 id 是 aapt 打包时分配的，抖音升级后可能变化；届时用「详细调试日志」
 * 的点击探针重新抓一次即可。
 */
internal object LocalDigg {

    /** 点赞按钮（FeedRightScaleView 里包着点赞图标的 FrameLayout）。 */
    private const val LIKE_BUTTON_ID = 0x7f0a309c

    /** 点赞数文字（点赞按钮的兄弟节点）。 */
    private const val LIKE_COUNT_ID = 0x7f0a309d

    private const val DIGG_ICON_CLASS = "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView"

    private const val SAMPLE_LIMIT = 60
    private var sampleCount = 0

    fun install(module: XposedModule, rules: RuleSource) {
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
                    applyLocalLike(view)
                    // 吞掉：抖音的点赞逻辑不执行，请求不会发出
                    return@intercept true
                }

                chain.proceed()
            }
            Diag.log(
                "digg",
                "已挂钩 View.performClick，命中 #0x${LIKE_BUTTON_ID.toString(16)} 即本地点赞",
            )
        }.onFailure {
            Diag.log("digg", "LocalDigg 挂载失败: $it")
        }
    }

    private fun applyLocalLike(button: View) {
        val icon = findIcon(button)
        val count = findCount(button)

        val liked = icon?.isSelected != true
        icon?.let {
            it.isSelected = liked
            it.refreshDrawableState()
        }

        val before = count?.text?.toString()
        if (count != null) {
            count.text = bumpCount(before, if (liked) 1 else -1)
        }

        if (sampleCount++ < SAMPLE_LIMIT) {
            Diag.log(
                "digg",
                "本地点赞 liked=$liked 图标=${icon?.javaClass?.simpleName ?: "未找到"}" +
                    " 点赞数 $before -> ${count?.text ?: "未找到"}",
            )
        }
    }

    /** 按钮内部找点赞图标：优先 DiggAnimationView，退化到第一个 ImageView。 */
    private fun findIcon(button: View): ImageView? {
        var fallback: ImageView? = null
        fun walk(node: View) {
            when {
                node.javaClass.name == DIGG_ICON_CLASS -> {
                    (node as? ImageView)?.let { fallback = it }
                    return
                }
                node is ImageView && fallback == null -> fallback = node
            }
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) walk(node.getChildAt(i))
            }
        }
        walk(button)
        // DiggAnimationView 优先级最高，直接再找一次
        return findDiggAnimationView(button) ?: fallback ?: (button as? ImageView)
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

    /** 点赞数是按钮的兄弟节点（同属那个 LinearLayout）。 */
    private fun findCount(button: View): TextView? {
        val parent = button.parent as? View
        parent?.findViewById<View>(LIKE_COUNT_ID)?.let { if (it is TextView) return it }
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

    private fun trimZero(value: Double): String =
        (Math.round(value * 10.0) / 10.0).toString().removeSuffix(".0")
}

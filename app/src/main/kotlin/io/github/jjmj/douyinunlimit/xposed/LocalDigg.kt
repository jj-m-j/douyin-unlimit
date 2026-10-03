package io.github.jjmj.douyinunlimit.xposed

import io.github.libxposed.api.XposedModule

/**
 * 让点赞「不回滚」——**不拦截点击，只拦截驳回后的重渲染**。
 *
 * ## 为什么不能拦点击（走过的弯路，记下来避免重蹈）
 *
 * 拦截点击在触摸层吞掉事件，会让抖音的手势检测只看到一次点击，于是：
 *
 *   - 双击被抖音判定成**单击** → 视频被暂停/播放
 *   - 抖音收不到完整的双击 → **它自己的双击特效不会播**
 *
 * 这两件事是因果绑定的：只要在触摸层拦，就必然丢掉原生的手感与特效。
 * 之前的版本正是在这里反复失败。
 *
 * ## 现在拦哪一步
 *
 * 让抖音完整走完它自己的一套（乐观 +1、原生动画、原生双击特效、发请求都在），
 * 只拦最后那一步：
 *
 *   用户点赞
 *     → 抖音乐观 +1 + 播原生特效 + 发请求
 *     → 服务端驳回
 *     → GlobalDiggStateManager 广播 (LX/0tvw)
 *     → VideoDiggView.onEventDiggUpdate(...)   ← 【驳回后重刷 UI 的入口】
 *     → 图标与数字被刷回未点赞
 *
 * `onEventDiggUpdate` 是 EventBus 订阅方法，**方法名没有被混淆**，比较稳定。
 * 跳过它，驳回后的重渲染就不发生，UI 停在已点赞状态。
 *
 * ## 已知限制
 *
 * 抖音的数据模型仍然是「未点赞」（服务端驳回是事实，改不了）。
 * 所以当这条视频的视图被回收重绑、或列表整体刷新时，图标会按模型刷回未点赞。
 * 这个方案保证的是「当次点赞当下不回滚」。
 */
internal object LocalDigg {

    private const val DIGG_WIDGET = "com.ss.android.ugc.aweme.feed.ui.VideoDiggView"

    /** 驳回后重刷 UI 的 EventBus 订阅方法名。 */
    private const val ON_DIGG_UPDATE = "onEventDiggUpdate"

    /** 兜底：事件对象的类名片段。 */
    private const val EVENT_TYPE_HINT = "0tvw"

    private const val SAMPLE_LIMIT = 40
    private var blockedCount = 0

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val widget = runCatching { Class.forName(DIGG_WIDGET, false, loader) }.getOrNull()
        if (widget == null) {
            Diag.log("digg", "找不到 $DIGG_WIDGET")
            return
        }

        val target = widget.declaredMethods.firstOrNull {
            it.name == ON_DIGG_UPDATE && it.parameterCount == 1
        } ?: widget.declaredMethods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0].name.contains(EVENT_TYPE_HINT)
        }

        if (target == null) {
            Diag.log("digg", "$DIGG_WIDGET 里没找到点赞更新事件入口")
            return
        }

        runCatching {
            module.hook(target).intercept { chain ->
                if (!rules.blockDiggUpload()) return@intercept chain.proceed()

                if (blockedCount++ < SAMPLE_LIMIT) {
                    Diag.log("digg", "拦下回滚重渲染（${target.name}）")
                }
                // 不执行重渲染：UI 停在已点赞状态
                null
            }
            Diag.log("digg", "已挂钩 ${target.name}(...)——驳回后不回滚")
        }.onFailure {
            Diag.log("digg", "挂载失败: $it")
        }
    }
}

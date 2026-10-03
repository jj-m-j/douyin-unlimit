package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 干掉聊天里「发送状态」那一套指示：左侧的红感叹号 + 「由于违反社区规定…」那段文字。
 *
 * 逆向 抖音 40.2.0：
 *
 *   LX/179c （基类，发送状态指示）        a: Message   b: ImageView
 *     LIZ()    -> setImageResource + ImageView.setVisibility(VISIBLE)   // 显示图标
 *     LIZLLL() -> 隐藏
 *     LJ(m)    -> 消息状态变化时调用 LIZ()（虚分派，会走到子类实现）
 *
 *   StatusIconWithText extends LX/179c    f: DmtTextView
 *     LIZ()  -> invoke-super LIZ()  然后设置并显示 f 的文字
 *     LJI()  -> 设置并显示 f 的文字
 *     LIZJ() -> 把图标和文字都藏起来，再调 LJI()
 *
 * 图标和文字属于**同一个组件**，所以按类名让它的「显示」方法失效即可，
 * 不需要逐个抓控件 id——id 方案在这个场景有两个硬伤：
 *   1. 同一个资源 id 可能被别的界面复用（例如 0x7f0aa9d7 同时也是会话列表的标题，
 *      曾经把消息页的会话名一起隐藏了）
 *   2. 控件在 RecyclerView 里被复用，rebind 时会重新显示
 *
 * 只拦截「显示」，不动 LIZLLL / LIZJ 这些隐藏路径，避免和原有状态机打架。
 */
internal object SendStatusGuard {

    private const val TAG = "DouyinUnlimit"

    private const val STATUS_ICON_WITH_TEXT =
        "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.status.sendstatus.StatusIconWithText"

    /** 负责把图标 / 文字显示出来的方法。找不到就退化成「所有 public 无参 void 方法都空操作」。 */
    private val SHOW_METHOD_NAMES = listOf("LIZ", "LJI")

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(STATUS_ICON_WITH_TEXT, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, TAG, "SendStatusGuard: 找不到 $STATUS_ICON_WITH_TEXT")
            return
        }

        val named = SHOW_METHOD_NAMES.mapNotNull { name ->
            clazz.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
        }
        val targets = if (named.isNotEmpty()) {
            named
        } else {
            clazz.declaredMethods.filter {
                it.parameterCount == 0 &&
                    it.returnType == Void.TYPE &&
                    !it.isSynthetic
            }
        }

        val hooked = mutableListOf<String>()
        for (method in targets) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (rules.hideSendStatus()) return@intercept null
                    chain.proceed()
                }
                hooked += method.name
            }
        }

        module.log(
            Log.INFO,
            TAG,
            if (hooked.isEmpty()) {
                "SendStatusGuard: 类找到了但没有可挂的方法"
            } else {
                "SendStatusGuard hooked: ${hooked.joinToString(" | ")}"
            },
        )
    }
}

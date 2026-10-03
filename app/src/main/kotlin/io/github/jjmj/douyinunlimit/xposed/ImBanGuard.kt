package io.github.jjmj.douyinunlimit.xposed

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier

/**
 * 干掉消息 tab 顶部那条「消息发送功能已被禁止使用」横幅。
 *
 * 逆向 抖音 40.2.0 的链路：
 *
 *   ChatBanTipsLogic (extends PriorityLogic)   // 显示/隐藏判定
 *     LJLLLLLL()V
 *       banInfo   = LX/0xtl.LIZ()                   // 本地缓存的封禁信息
 *       punishIds = banInfo?.LJ() ?: emptyList()    // 封禁记录 id 列表
 *       shownIds  = IMKevaConfig 里已展示过的 id
 *       if (punishIds.isEmpty()) { LJLLL(); return }             // 隐藏
 *       for (id in punishIds) if (id !in shownIds) { LJLLLL(); return }  // 显示
 *       LJLLL()                                                  // 隐藏
 *
 *   ChatBanTipsUI (extends RipsUI)             // 渲染，整个类只服务这一条横幅
 *     a = DuxImageView ← 0x7f0a5e36  （铃铛图标）
 *     b = DuxTextView  ← 0x7f0ac401  （标题文字，已用 Layout Inspect 在真机核对）
 *
 * 这个 Logic 类只负责这条横幅，所以有两层处理：
 *   1. 直接让显示判定不执行 —— 横幅永远不会被 show
 *   2. 兜底：把 ImBanInfo 的封禁列表置空，让它自己走「no punish id → 隐藏」分支
 */
internal object ImBanGuard {

    private const val TIPS_LOGIC =
        "com.ss.android.ugc.aweme.im.sdk.module.session.rips.sessionheader.tips.ChatBanTipsLogic"
    private const val BAN_INFO =
        "com.ss.android.ugc.aweme.im.business.baninfo.ImBanInfo"

    /**
     * 负责显示/隐藏判定的方法名。这是 R8 改名后的名字，
     * 找不到时退化成「屏蔽该类所有 public 的无参 void 方法」。
     */
    private val VISIBILITY_METHOD_NAMES = listOf("LJLLLLLL")

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookTipsVisibility(module, loader, rules)
        hookBanInfo(module, loader, rules)
    }

    // ---------------------------------------------------------------- 横幅显示判定

    private fun hookTipsVisibility(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(TIPS_LOGIC, false, loader) }.getOrNull() ?: return

        val named = VISIBILITY_METHOD_NAMES.mapNotNull { name ->
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

        for (method in targets) {
            runCatching {
                module.hook(method).intercept { chain ->
                    if (rules.hideImBanTips()) return@intercept null
                    chain.proceed()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 封禁信息兜底

    private fun hookBanInfo(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(BAN_INFO, false, loader) }.getOrNull() ?: return

        // punishIdList -> 空
        runCatching {
            val getter = clazz.getDeclaredMethod("LJ")
            module.hook(getter).intercept { chain ->
                if (rules.fakeNoBanInfo()) emptyList<Any>() else chain.proceed()
            }
        }

        // isPunished -> false
        runCatching {
            val getter = clazz.getDeclaredMethod("LJFF")
            module.hook(getter).intercept { chain ->
                if (rules.fakeNoBanInfo()) false else chain.proceed()
            }
        }
    }
}

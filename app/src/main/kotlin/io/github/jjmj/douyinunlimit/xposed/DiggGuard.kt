package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.Collections
import kotlin.jvm.functions.Function2

/**
 * 让点赞「留得住」。
 *
 * 抖音的点赞是乐观更新：点下去 UI 立刻变成已赞、点赞数 +1，同时发请求。
 * 账号被限制时服务端驳回，客户端再把 UI 回滚成原样——就是「+1 又弹回去」。
 *
 * 逆向 抖音 40.2.0 找到的节点：
 *
 *   DiggViewModel.a : LX/13YF        // originState，原始点赞状态快照
 *   DiggViewModel.b : Aweme          // 当前视频
 *   HH1(Activity, String enterMethod, Function2<Boolean, String, Unit> callback)
 *       // 点赞入口，调用方是 IconDiggPresenter 的点击监听
 *   MH1(...)                          // 取消点赞入口
 *
 *   IconDiggPresenter.Z1()V           // 把当前状态画到图标上（状态变化时被调用）
 *   IconDiggPresenter.Y1(Z)V          // 应用选中态
 *
 *   GlobalDiggStateManager.LIZ(String aid, boolean digged)
 *       // 全局广播点赞状态，让所有页面的 UI 一起更新
 *
 * 本版为**诊断版**：三个关键节点都打日志，用来确定回滚到底发生在哪一层。
 * 同时保留两个真实尝试：
 *   A. 把 HH1 的回调包一层，让接收方永远收到 success = true（上一版无效，这次带日志验证）
 *   B. 全局状态广播不允许「已赞 -> 未赞」降级
 */
internal object DiggGuard {

    private const val DIGG_VIEW_MODEL =
        "com.ss.android.ugc.aweme.feed.quick.component.digg.DiggViewModel"

    private const val ICON_DIGG_PRESENTER =
        "com.ss.android.ugc.aweme.feed.quick.component.digg.icondigg.IconDiggPresenter"

    private const val GLOBAL_STATE_MANAGER =
        "com.ss.android.ugc.aweme.feed.quick.component.digg.GlobalDiggStateManager"

    private const val LIKE_METHOD = "HH1"
    private const val FUNCTION2 = "kotlin.jvm.functions.Function2"

    /** 用户点过赞的 aid。用于 B：不允许把这些 aid 降级成未赞。 */
    private val likedAids: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookLikeEntry(module, loader, rules)
        hookIconRender(module, loader)
        hookGlobalState(module, loader, rules)
    }

    // ---------------------------------------------------------------- A. 点赞入口

    private fun hookLikeEntry(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(DIGG_VIEW_MODEL, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, Diag.TAG, "DiggGuard: 找不到 $DIGG_VIEW_MODEL")
            return
        }

        val target = clazz.declaredMethods.firstOrNull { it.name == LIKE_METHOD }
            ?: clazz.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size == 3 && method.parameterTypes[2].name == FUNCTION2
            }

        if (target == null) {
            module.log(Log.WARN, Diag.TAG, "DiggGuard: 点赞入口方法没找到")
            return
        }

        runCatching {
            module.hook(target).intercept { chain ->
                Diag.log("HH1", "HH1 点赞入口被调用")

                if (!rules.stickyDigg()) return@intercept chain.proceed()

                val args = chain.args
                val callback = args.getOrNull(2)
                if (callback is Function2<*, *, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val wrapped = AlwaysSuccess(callback as Function2<Boolean, String, Unit>)
                    chain.proceed(arrayOf(args[0], args[1], wrapped))
                } else {
                    chain.proceed()
                }
            }
            module.log(Log.INFO, Diag.TAG, "DiggGuard hooked HH1: ${target.parameterTypes.joinToString(",") { it.simpleName }}")
        }.onFailure {
            module.log(Log.ERROR, Diag.TAG, "DiggGuard HH1 挂载失败", it)
        }
    }

    /** 把接收方的「成功?」恒定改写成 true，其余参数原样透传。 */
    private class AlwaysSuccess(
        private val inner: Function2<Boolean, String, Unit>,
    ) : Function2<Boolean, String, Unit> {
        override fun invoke(p1: Boolean, p2: String) {
            Diag.log("cb", "HH1 回调：原始 success=$p1 msg=$p2 -> 改写为 true")
            inner.invoke(true, p2)
        }
    }

    // ---------------------------------------------------------------- 图标渲染

    private fun hookIconRender(module: XposedModule, loader: ClassLoader) {
        val clazz = runCatching { Class.forName(ICON_DIGG_PRESENTER, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, Diag.TAG, "DiggGuard: 找不到 $ICON_DIGG_PRESENTER")
            return
        }

        val z1 = clazz.declaredMethods.firstOrNull { it.name == "Z1" && it.parameterCount == 0 }
        val y1 = clazz.declaredMethods.firstOrNull { it.name == "Y1" && it.parameterCount == 1 }

        val hooked = mutableListOf<String>()

        if (z1 != null) {
            runCatching {
                module.hook(z1).intercept { chain ->
                    Diag.log("Z1", "Z1 重画点赞图标")
                    chain.proceed()
                }
                hooked += "Z1"
            }
        }

        if (y1 != null) {
            runCatching {
                module.hook(y1).intercept { chain ->
                    Diag.log("Y1", "Y1 应用选中态 selected=${chain.args[0]}")
                    chain.proceed()
                }
                hooked += "Y1"
            }
        }

        module.log(
            Log.INFO,
            Diag.TAG,
            if (hooked.isEmpty()) "DiggGuard: IconDiggPresenter 没有可挂的方法" else "DiggGuard hooked icon: ${hooked.joinToString("|")}",
        )
    }

    // ---------------------------------------------------------------- B. 全局状态广播

    private fun hookGlobalState(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(GLOBAL_STATE_MANAGER, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, Diag.TAG, "DiggGuard: 找不到 $GLOBAL_STATE_MANAGER")
            return
        }

        val target = clazz.declaredMethods.firstOrNull {
            it.name == "LIZ" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (target == null) {
            module.log(Log.WARN, Diag.TAG, "DiggGuard: GlobalDiggStateManager.LIZ 没找到")
            return
        }

        runCatching {
            module.hook(target).intercept { chain ->
                val aid = chain.args[0] as? String
                val digged = chain.args[1] as Boolean
                Diag.log("global", "全局状态广播 aid=$aid digged=$digged")

                if (aid != null && digged) likedAids.add(aid)

                // 不允许「点过赞 -> 未赞」的降级
                if (rules.stickyDigg() && aid != null && !digged && likedAids.contains(aid)) {
                    Diag.log("global", "拦截降级：aid=$aid 保持已赞")
                    return@intercept chain.proceed(arrayOf(aid, true))
                }

                chain.proceed()
            }
            module.log(Log.INFO, Diag.TAG, "DiggGuard hooked GlobalDiggStateManager.LIZ(String,boolean)")
        }.onFailure {
            module.log(Log.ERROR, Diag.TAG, "DiggGuard 全局状态挂载失败", it)
        }
    }
}

package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import kotlin.jvm.functions.Function2

/**
 * 让点赞「留得住」。
 *
 * 抖音的点赞是乐观更新：点下去 UI 立刻变成已赞、点赞数 +1，同时发请求。
 * 账号被限制时服务端驳回，客户端再把 UI 回滚成原样——就是你看到的「+1 又弹回去」。
 *
 * 逆向 抖音 40.2.0 找到的回滚入口：
 *
 *   DiggViewModel.a : LX/13YF        // originState，原始点赞状态快照（由 GH1 从 Aweme.statistics 构造）
 *   DiggViewModel.b : Aweme          // 当前视频
 *
 *   HH1(Activity, String enterMethod, Function2<Boolean, String, Unit> callback)
 *       // 点赞入口，失败时会回调 (false, msg)，接收方据此回滚成 originState
 *       // 调用方：IconDiggPresenter 的点击监听、网络成功回调 LX/1J81.onSuccess
 *
 *   MH1(...)  // 取消点赞入口
 *
 * 做法：不碰状态机、不伪造网络，只在 `HH1` 这里**把回调包一层**，
 * 让接收方永远收到 success = true。回滚分支因此不会执行，其余流程（埋点、动画、
 * 全局状态广播）全部照旧。这比直接改 UI 状态干净得多——不会出现
 * 「图标点赞了但数据没变」这种自相矛盾的状态。
 *
 * 注意：只处理「点赞」不处理「取消点赞」。因为取消点赞同样会被服务端驳回，
 * 而让它照常失败刚好就是我们要的结果——状态保持已赞。
 */
internal object DiggGuard {

    private const val TAG = "DouyinUnlimit"

    private const val DIGG_VIEW_MODEL =
        "com.ss.android.ugc.aweme.feed.quick.component.digg.DiggViewModel"

    private const val LIKE_METHOD = "HH1"
    private const val FUNCTION2 = "kotlin.jvm.functions.Function2"

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(DIGG_VIEW_MODEL, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, TAG, "DiggGuard: 找不到 $DIGG_VIEW_MODEL")
            return
        }

        // 方法名是 R8 改名后的，加一层结构兜底：三个参数、最后一个是 Function2 的只有点赞入口
        val target = clazz.declaredMethods.firstOrNull { it.name == LIKE_METHOD }
            ?: clazz.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size == 3 &&
                    method.parameterTypes[2].name == FUNCTION2
            }

        if (target == null) {
            module.log(Log.WARN, TAG, "DiggGuard: 点赞入口方法没找到")
            return
        }

        runCatching {
            module.hook(target).intercept { chain ->
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
            module.log(
                Log.INFO,
                TAG,
                "DiggGuard hooked: ${target.name}${target.parameterTypes.joinToString(",", "(", ")") { it.simpleName }}",
            )
        }.onFailure {
            module.log(Log.ERROR, TAG, "DiggGuard 挂载失败", it)
        }
    }

    /** 把接收方的「成功?」恒定改写成 true，其余参数原样透传。 */
    private class AlwaysSuccess(
        private val inner: Function2<Boolean, String, Unit>,
    ) : Function2<Boolean, String, Unit> {
        override fun invoke(p1: Boolean, p2: String) {
            inner.invoke(true, p2)
        }
    }
}

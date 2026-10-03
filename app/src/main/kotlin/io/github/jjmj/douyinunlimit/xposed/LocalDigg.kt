package io.github.jjmj.douyinunlimit.xposed

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 让点赞「不回滚」——不拦点击、不拦请求，**只拦驳回后的那一次回滚**。
 *
 * ## 拦截点是怎么定下来的
 *
 * 抖音 40.2.0 的点赞链路（`com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter`）：
 *
 *   LJJJJL(aweme)            handle_digg_click：点击入口
 *       LJJLIIJ(aweme,true,..)      乐观设置「已赞」  <- 唯一写 Aweme.diggSelected 的地方
 *       aweme.diggSelected = 1
 *       LJJLIIIJJI(aweme,...)       发请求
 *           Gl(Pair)                    成功回调
 *           wq(Exception)               失败回调（打 feed_digg_error_monitor 埋点）
 *               LJJJJZ(Exception)       **回滚**：把状态取反写回 diggSelected，
 *                                       弹失败提示、通知监听者、post LiveData
 *
 * `LJJJJZ` 的调用者只有 `wq`（错误回调）和 `LX/19zz.run()`（它的转发包装），
 * 也就是说「失败后撤销乐观更新」这件事**只有这一个出口**。跳过它，UI 就停在已赞状态。
 *
 * ## 之前挂错在哪（记下来，别再走一遍）
 *
 * v1.13 拦的是 `VideoDiggView.onEventDiggUpdate(LX/0tvw;)V`。那确实是 EventBus 订阅方法，
 * 命中后也会调 `LJJIIJZLJL(Aweme,ZZ)` 重刷视图——但它**只在跨页面同步点赞状态时才广播**，
 * 驳回回滚根本不走它。真机日志里 hook 挂上了、点了赞、一条命中记录都没有，
 * 这就是「挂上但从未命中」的铁证。
 *
 * ## 为什么不能拦点击（更早的弯路）
 *
 * 在触摸层吞掉事件，抖音的手势检测只会看到一次点击，于是：
 *   - 双击被判成单击 -> **视频被暂停**（用户实际反馈过这个现象）
 *   - 抖音收不到完整双击 -> **它自己的双击特效不会播**
 * 这两件事是因果绑定的：只要在触摸层拦，就必然丢掉原生的手感与特效。
 *
 * ## 已知限制
 *
 * 服务端确实驳回了这次点赞，`Aweme.userDigg` 仍是未赞（我们只改本地的 `diggSelected`，
 * 且这里连 `diggSelected` 都不撤销）。所以下拉刷新、切换 tab 重新拉列表之后，
 * 图标会按服务端数据恢复。这个方案保证的是「点赞当下不回滚」。
 */
internal object LocalDigg {

    private const val PRESENTER = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter"

    /** 失败后的回滚。整个链路里唯一会撤销乐观点赞的地方。 */
    private const val M_REVERT = "LJJJJZ"

    /** handle_digg_click：点击入口。仅用于诊断。 */
    private const val M_CLICK = "LJJJJL"

    /** 发送点赞请求。仅用于诊断。 */
    private const val M_SEND = "LJJLIIIJJI"

    private const val SAMPLE_LIMIT = 40

    private var revertHits = 0
    private var clickHits = 0
    private var sendHits = 0

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val presenter = runCatching { Class.forName(PRESENTER, false, loader) }.getOrNull()
        if (presenter == null) {
            Diag.log("digg", "找不到 $PRESENTER（抖音版本可能变了）")
            return
        }

        val revert = presenter.declaredMethods.firstOrNull {
            it.name == M_REVERT && it.parameterCount == 1 &&
                Exception::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: presenter.declaredMethods.firstOrNull {
            it.name == M_REVERT && it.parameterCount == 1
        }

        if (revert == null) {
            Diag.log("digg", "没找到回滚入口 $M_REVERT(Exception)，点赞保护不会生效")
        } else {
            hookRevert(module, revert, rules)
        }

        // 诊断钩子：把「点击 -> 发请求 -> 回滚」三段分开记录。
        // 这样日志能直接区分「没挂上 / 挂上没命中 / 命中但无效」，不用再靠猜。
        hookTrace(module, presenter, M_CLICK, 1, "digg-click")
        hookTrace(module, presenter, M_SEND, 3, "digg-send")
    }

    // ---------------------------------------------------------------- 拦截

    private fun hookRevert(module: XposedModule, revert: Method, rules: RuleSource) {
        runCatching {
            module.hook(revert).intercept { chain ->
                val protect = rules.stickyDigg()

                if (revertHits++ < SAMPLE_LIMIT) {
                    Diag.log(
                        "digg",
                        if (protect) {
                            "拦下驳回回滚 —— 点赞停留在已赞状态"
                        } else {
                            "驳回回滚发生（开关已关，放行）"
                        },
                    )
                }

                if (protect) null else chain.proceed()
            }
            Diag.log("digg", "已挂钩 ${revert.name}(Exception) —— 驳回后不回滚")
        }.onFailure {
            Diag.log("digg", "回滚入口挂载失败: $it")
        }
    }

    // ---------------------------------------------------------------- 诊断

    private fun hookTrace(
        module: XposedModule,
        presenter: Class<*>,
        name: String,
        parameterCount: Int,
        label: String,
    ) {
        val method = presenter.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        } ?: run {
            Diag.log("digg", "诊断钩子找不到 $name/$parameterCount（$label）")
            return
        }

        runCatching {
            module.hook(method).intercept { chain ->
                val hits = when (label) {
                    "digg-click" -> ++clickHits
                    else -> ++sendHits
                }
                if (hits <= SAMPLE_LIMIT) {
                    Diag.debug("digg", "$label ${method.name}(${describe(chain.args)})")
                }
                chain.proceed()
            }
        }.onFailure { Diag.log("digg", "$label 挂载失败: $it") }
    }

    /** 只打印出「哪个参数是点赞态」就够定位，不做完整序列化。 */
    private fun describe(args: List<Any?>): String {
        val out = StringBuilder()
        for (arg in args) {
            if (out.isNotEmpty()) out.append(", ")
            when (arg) {
                null -> out.append("null")
                is String -> out.append(arg)
                is Boolean -> out.append(arg)
                is Number -> out.append(arg)
                else -> {
                    out.append(arg.javaClass.simpleName)
                    aidOf(arg)?.let { out.append("(").append(it).append(")") }
                }
            }
        }
        return out.toString()
    }

    private fun aidOf(aweme: Any): String? = runCatching {
        aweme.javaClass.getMethod("getAid").invoke(aweme) as? String
    }.getOrNull()
}

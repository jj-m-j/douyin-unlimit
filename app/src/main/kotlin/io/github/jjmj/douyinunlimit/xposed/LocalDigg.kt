package io.github.jjmj.douyinunlimit.xposed

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 让点赞「不回滚」——不拦点击、不拦请求，**只拦驳回后的那一次回滚**。
 *
 * ## 抖音 40.2.0 的点赞链路
 *
 * `com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter`：
 *
 * ```
 * LJJJJL(aweme)              handle_digg_click：点击入口
 *   LJJLIIJ(aweme,true,..)       乐观设为已赞     ← 唯一写 Aweme.diggSelected 的地方
 *   aweme.diggSelected = 1
 *   LJJLIIIJJI(aweme,...)        发请求
 *       Gl(Pair)                     成功回调
 *       wq(Exception)                失败回调（打 feed_digg_error_monitor 埋点）
 *           LJJJJZ(Exception)        ★ 回滚：取反写回 diggSelected、弹失败提示、
 *                                     通知 LX/1J7T、post LX/1A7e 到 LiveData
 * ```
 *
 * ## 怎么定位回滚方法（跨版本的关键）
 *
 * **不写死 `LJJJJZ`。** 那个名字是混淆产物，抖音换版本就会变。
 * 稳定的形状是：**「失败后撤销乐观更新」的方法必然收一个 `Exception`**。
 * 而 `FeedDiggPresenter` 里收 `Exception` 的方法正好只有这两个：
 *
 * | 方法 | 作用 |
 * |---|---|
 * | `wq(Exception)` | 请求失败回调，会转调下面那个 |
 * | `LJJJJZ(Exception)` | 回滚本体 |
 *
 * 所以直接按「收 Exception 的实例方法」把它们全挂上。跳过它们，
 * 无论抖音怎么改名，这一次失败都不会被撤销。
 *
 * `FeedDiggPresenter` 这个类名本身是 R8 保留的可读业务名，跨版本基本不变；
 * 万一变了，日志里会明确写出「找不到」，而不是功能默默失效。
 *
 * ## 之前挂错在哪（记下来，别再走一遍）
 *
 * v1.13 拦的是 `VideoDiggView.onEventDiggUpdate(LX/0tvw;)V`。那确实是 EventBus 订阅方法，
 * 但它**只在跨页面同步点赞状态时才广播**，驳回回滚根本不走它。真机日志里
 * hook 挂上了、用户点了赞、一条命中记录都没有 —— 「挂上但从未命中」的铁证，见 §7.6。
 *
 * ## 为什么不能拦点击（更早的弯路）
 *
 * 在触摸层吞掉事件，抖音的手势检测只会看到一次点击，于是双击被判成单击 -> 视频被暂停，
 * 而且它自己的双击特效不会播。这两件事是因果绑定的：**只要在触摸层拦，
 * 就必然丢掉原生的手感与特效**。这是物理约束，不是写法问题。
 *
 * ## 已知限制
 *
 * 服务端确实驳回了这次点赞，`Aweme.userDigg` 仍是未赞。所以下拉刷新、
 * 换一批视频之后图标会按服务端数据恢复。这个方案保证的是「**点赞当下不回滚**」。
 */
internal object LocalDigg {

    /** 业务类名，跨版本基本不变。 */
    private const val PRESENTER = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedDiggPresenter"

    private const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"

    private const val SAMPLE_LIMIT = 40

    private var revertHits = 0
    private var traceHits = 0

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val presenter = Targets.load(loader, PRESENTER)
        if (presenter == null) {
            Diag.log("digg", "找不到 $PRESENTER（抖音版本可能变了）")
            return
        }

        // ---- 回滚：收 Exception 的方法就是「失败处理」，全挂上 ----
        val revertTargets = Targets.methodsTaking(presenter, Exception::class.java)
        if (revertTargets.isEmpty()) {
            Diag.log("digg", "${presenter.simpleName} 里没有收 Exception 的方法，点赞保护不会生效")
        } else {
            hookRevert(module, presenter, revertTargets, rules)
        }

        // ---- 诊断钩子：把「点击 -> 发请求 -> 回滚」三段分开记录 ----
        // 没有它就只能看到「功能失效」，看不出是没挂上、没命中、还是命中但无效。
        val aweme = Targets.load(loader, AWEME)
        if (aweme == null) {
            Diag.log("digg", "找不到 $AWEME，跳过点赞链路诊断钩子")
            return
        }

        for (method in Targets.methodsWithParamAt(presenter, 0, 1, aweme)) {
            if (method.returnType == Void.TYPE) hookTrace(module, method, "digg-click")
        }
        for (method in Targets.methodsWithParamAt(presenter, 0, 3, aweme)) {
            hookTrace(module, method, "digg-send")
        }
    }

    // ---------------------------------------------------------------- 拦截

    private fun hookRevert(
        module: XposedModule,
        presenter: Class<*>,
        targets: List<Method>,
        rules: RuleSource,
    ) {
        val hooked = mutableListOf<String>()

        for (method in targets) {
            runCatching {
                module.hook(method).intercept { chain ->
                    val protect = rules.stickyDigg()

                    if (revertHits++ < SAMPLE_LIMIT) {
                        Diag.log(
                            "digg",
                            if (protect) {
                                "拦下驳回回滚（${method.name}）—— 点赞停留在已赞状态"
                            } else {
                                "驳回回滚发生（${method.name}，开关已关，放行）"
                            },
                        )
                    }

                    if (protect) null else chain.proceed()
                }
                hooked += method.name
            }.onFailure {
                Diag.log("digg", "${method.name}(Exception) 挂载失败: $it")
            }
        }

        Diag.log(
            "digg",
            "点赞保护已挂钩 ${presenter.simpleName}.[${hooked.joinToString("/")}](Exception) —— 驳回后不回滚",
        )
    }

    // ---------------------------------------------------------------- 诊断

    private fun hookTrace(module: XposedModule, method: Method, label: String) {
        runCatching {
            module.hook(method).intercept { chain ->
                if (traceHits++ < SAMPLE_LIMIT) {
                    Diag.debug("digg", "$label ${method.name}(${describe(chain.args)})")
                }
                chain.proceed()
            }
        }.onFailure { Diag.log("digg", "$label 挂载失败: $it") }
    }

    /** 只打印出「哪个参数是点赞态、哪个是 aid」就够定位，不做完整序列化。 */
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

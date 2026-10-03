package io.github.jjmj.douyinunlimit.xposed

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拦截点赞请求，让它根本不发出去。
 *
 * ## 拦在哪一层
 *
 * 抖音内部的点赞 API 类被混淆得很散（至少 6 个类持有 `/aweme/v1/commit/item/digg/`），
 * 按「接口类」去拦是打地鼠。所以这里挂两个层级：
 *
 *  1. **`okhttp3.OkHttpClient.newCall(Request)`** ← 最外层
 *     只要请求走 OkHttp，无论上层是 Retrofit、TTNet 还是手写，都必须过这里。
 *     这个名字是公开 API、没被混淆，所以最可靠。
 *     拿到返回的 Call 实例后，懒挂它**具体实现类**（真名被混淆了，只能运行时发现）
 *     的 enqueue / execute —— 之后就能按 URL 拦。
 *
 *  2. **`com.bytedance.retrofit2.SsHttpCall`** ← Retrofit 专线
 *     所有 Retrofit 请求的 Call 实现。
 *
 * ## 为什么拦 enqueue 不拦 execute
 *
 * `enqueue` 返回 void，不调用它 = 这张请求永远没有结果：回调不触发 → 没有失败 →
 * 不会触发回滚。乐观更新（图标变红、数字 +1）原样保留。
 *
 * `execute` 返回 Response，返回 null 会让调用方 NPE，所以只记录不拦截，
 * 先用日志确认点赞到底走哪条。
 */
internal object NetGuard {

    private const val SS_HTTP_CALL = "com.bytedance.retrofit2.SsHttpCall"
    private const val OKHTTP_CLIENT = "okhttp3.OkHttpClient"
    private const val REQUEST_TYPE = "okhttp3.Request"

    /** 命中任意一条即视为点赞请求。 */
    private val DIGG_MARKERS = listOf(
        "/commit/item/digg/",
        "/digg/multi/",
        "/item/digg/",
    )

    /** 已懒挂过的具体 Call 实现类。 */
    private val hookedCallClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    /** 非点赞请求只抽样打印前几条，用来证明 OkHttp 链路是通的。 */
    private const val SAMPLE_LIMIT = 30
    private val sample = AtomicInteger(0)

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        hookOkHttpNewCall(module, loader, rules)
        hookSsHttpCall(module, loader, rules)
    }

    // ---------------------------------------------------------------- 最外层：OkHttp

    private fun hookOkHttpNewCall(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(OKHTTP_CLIENT, false, loader) }.getOrNull()
        if (clazz == null) {
            Diag.log("net", "找不到 $OKHTTP_CLIENT（可能不走 OkHttp）")
            return
        }

        val newCall = clazz.declaredMethods.firstOrNull { it.name == "newCall" && it.parameterCount == 1 }
        if (newCall == null) {
            Diag.log("net", "$OKHTTP_CLIENT 没有 newCall 方法")
            return
        }

        runCatching {
            module.hook(newCall).intercept { chain ->
                val url = urlOfRequest(chain.args.getOrNull(0))
                if (url != null) {
                    if (isDigg(url)) {
                        Diag.log("okhttp", "点赞请求 URL: $url")
                    } else if (sample.incrementAndGet() <= SAMPLE_LIMIT) {
                        Diag.log("okhttp", "请求样本: $url")
                    }
                }

                val call = chain.proceed()
                ensureCallClassHooked(module, call, rules)
                call
            }
            Diag.log("net", "已挂钩 OkHttpClient.newCall（最外层网）")
        }.onFailure {
            Diag.log("net", "OkHttpClient.newCall 挂载失败: $it")
        }
    }

    /**
     * 运行时发现 Call 的具体实现类并挂上 enqueue / execute。
     * 实现类真名被混淆了（按 RealCall 找不到），只能从实例反推；每个类只挂一次。
     */
    private fun ensureCallClassHooked(module: XposedModule, call: Any?, rules: RuleSource) {
        val cls = call?.javaClass ?: return
        if (!hookedCallClasses.add(cls)) return

        val requestField = cls.declaredFields
            .firstOrNull { it.type.name == REQUEST_TYPE && !Modifier.isStatic(it.modifiers) }
            ?.also { runCatching { it.isAccessible = true } }

        var hookedAny = false

        val enqueue = cls.declaredMethods.firstOrNull { it.name == "enqueue" }
        if (enqueue != null) {
            runCatching {
                module.hook(enqueue).intercept { chain ->
                    val url = urlOfRequest(requestField?.get(chain.thisObject))
                    if (url != null && rules.blockDiggUpload() && isDigg(url)) {
                        Diag.log("net", "已拦截点赞上传：$url")
                        return@intercept null
                    }
                    chain.proceed()
                }
                hookedAny = true
            }
        }

        val execute = cls.declaredMethods.firstOrNull { it.name == "execute" }
        if (execute != null) {
            runCatching {
                module.hook(execute).intercept { chain ->
                    val url = urlOfRequest(requestField?.get(chain.thisObject))
                    if (url != null && isDigg(url)) {
                        Diag.log("net", "点赞走了 execute（未拦截）：$url")
                    }
                    chain.proceed()
                }
                hookedAny = true
            }
        }

        Diag.log("net", "具体 Call 类 = ${cls.name}，挂载${if (hookedAny) "成功" else "失败"}")
    }

    // ---------------------------------------------------------------- Retrofit 专线

    private fun hookSsHttpCall(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(SS_HTTP_CALL, false, loader) }.getOrNull()
        if (clazz == null) {
            Diag.log("net", "找不到 $SS_HTTP_CALL")
            return
        }

        val requestField = clazz.declaredFields
            .firstOrNull { it.type.name == REQUEST_TYPE && !Modifier.isStatic(it.modifiers) }
            ?.also { runCatching { it.isAccessible = true } }

        val enqueue = clazz.declaredMethods.firstOrNull { it.name == "enqueue" }
        if (enqueue == null) {
            Diag.log("net", "$SS_HTTP_CALL 没有 enqueue")
            return
        }

        runCatching {
            module.hook(enqueue).intercept { chain ->
                val url = urlOfRequest(requestField?.get(chain.thisObject))
                if (url != null && rules.blockDiggUpload() && isDigg(url)) {
                    Diag.log("net", "已拦截点赞上传（Retrofit）：$url")
                    return@intercept null
                }
                chain.proceed()
            }
            Diag.log("net", "已挂钩 SsHttpCall.enqueue（Retrofit 专线）")
        }.onFailure {
            Diag.log("net", "SsHttpCall.enqueue 挂载失败: $it")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun isDigg(url: String): Boolean = DIGG_MARKERS.any { url.contains(it) }

    /** 反射调 request.url() 取串。 */
    private fun urlOfRequest(request: Any?): String? {
        if (request == null) return null
        return runCatching {
            request.javaClass.getMethod("url").invoke(request)?.toString()
        }.getOrNull()
    }
}

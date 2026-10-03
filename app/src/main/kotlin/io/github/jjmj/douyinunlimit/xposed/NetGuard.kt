package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 拦截点赞请求，让它根本不发出去。
 *
 * ## 为什么拦在 HTTP 层
 *
 * 抖音内部的点赞 API 类被混淆得很散（已确认至少 6 个类持有
 * `/aweme/v1/commit/item/digg/` 这个地址），而且真正发请求的那条链路上游
 * 还引用了 IFamiliarRecommendService，按「接口类」去拦是打地鼠。
 *
 * 但所有 Retrofit 请求最终都会经过 `com.bytedance.retrofit2.SsHttpCall`
 * （Retrofit 的 Call 实现），这是**单一收口点**——在这里按 URL 判断，
 * 不管上层换成哪个 API 类都躲不掉。
 *
 * ## 为什么只拦 enqueue 不拦 execute
 *
 * `enqueue` 返回 void，不调用它就等于这张请求永远没有结果：
 * 回调不触发 → 没有失败 → 不会触发回滚。乐观更新（图标变红、数字 +1）原样保留。
 *
 * `execute` 会返回一个 Response，直接返回 null 会让调用方 NPE，
 * 所以只记录不拦截，先看日志确认点赞到底走哪条。
 */
internal object NetGuard {

    private const val SS_HTTP_CALL = "com.bytedance.retrofit2.SsHttpCall"

    /** 命中任意一条即拦截。覆盖 /aweme/v1/commit/item/digg/ 与各种批量点赞接口。 */
    private val DIGG_URL_MARKERS = listOf(
        "/commit/item/digg/",
        "/digg/multi/",
        "/item/digg/",
    )

    fun install(module: XposedModule, loader: ClassLoader, rules: RuleSource) {
        val clazz = runCatching { Class.forName(SS_HTTP_CALL, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, Diag.TAG, "NetGuard: 找不到 $SS_HTTP_CALL")
            return
        }

        val requestField = clazz.declaredFields
            .firstOrNull { it.type.name == "okhttp3.Request" && !Modifier.isStatic(it.modifiers) }
            ?.also { runCatching { it.isAccessible = true } }

        if (requestField == null) {
            module.log(Log.WARN, Diag.TAG, "NetGuard: 在 $SS_HTTP_CALL 里没找到 okhttp3.Request 字段")
        }

        val enqueue = clazz.declaredMethods.firstOrNull { it.name == "enqueue" }
        if (enqueue != null) {
            runCatching {
                module.hook(enqueue).intercept { chain ->
                    val url = urlOf(chain.thisObject, requestField)
                    if (url != null && rules.blockDiggUpload() && isDigg(url)) {
                        Diag.log("net", "已拦截点赞上传：$url")
                        return@intercept null
                    }
                    chain.proceed()
                }
                module.log(Log.INFO, Diag.TAG, "NetGuard hooked: SsHttpCall.enqueue（拦截点赞上传）")
            }.onFailure { module.log(Log.ERROR, Diag.TAG, "NetGuard enqueue 挂载失败", it) }
        } else {
            module.log(Log.WARN, Diag.TAG, "NetGuard: 没有 enqueue 方法")
        }

        // execute 只记录，不拦截（返回 null 会让调用方 NPE）
        val execute = clazz.declaredMethods.firstOrNull { it.name == "execute" }
        if (execute != null) {
            runCatching {
                module.hook(execute).intercept { chain ->
                    val url = urlOf(chain.thisObject, requestField)
                    if (url != null && isDigg(url)) {
                        Diag.log("net", "点赞走了 execute（未拦截）：$url")
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun isDigg(url: String): Boolean = DIGG_URL_MARKERS.any { url.contains(it) }

    /** 反射拿 URL：SsHttpCall 里那个 okhttp3.Request 字段 → request.url()。 */
    private fun urlOf(target: Any?, requestField: Field?): String? = runCatching {
        val request = requestField?.get(target) ?: return null
        val url = request.javaClass.getMethod("url").invoke(request) ?: return null
        url.toString()
    }.getOrNull()
}

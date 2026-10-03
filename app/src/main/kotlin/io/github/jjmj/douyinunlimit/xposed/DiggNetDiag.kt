package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 点赞链路的网络层诊断。
 *
 * 逆向确认：`LX/1JCt` 是 `LX/1JCu`（Retrofit 接口）的静态包装层，
 * 它调用 `diggItem(...)` → `Future.get()` → 返回 `Pair<String, Integer>`
 * （first = 响应体，second = 状态码），再由下面这几条路投递给上层。
 *
 * 目前已知 9 个调用点：
 *
 *   Rx 路径（能优雅地「不投递」）
 *     Y/AOSubscribeS1015S0100000_38 -> subscribe$0 / $1 / $2
 *     Y/AOSubscribeS1001S0100000_15 -> subscribe$6 / $7 / $10
 *
 *   非 Rx 路径
 *     X/0ZFj -> invokeSuspend   （协程）
 *     X/0ZFk -> invokeSuspend   （协程）
 *     X/0ZFq -> call            （Callable）
 *     X/1Iti -> call            （Callable，走 LIZJ）
 *     Y/ACallableS319S0200000_38 -> call$1  （Callable，走 LIZJ）
 *
 * 本文件的唯一目的是在点赞时打出日志，确定实际走的是哪一条，
 * 以及服务端驳回时 `Pair` 里的真实内容（尤其响应体里有没有 digg_count）。
 * 不做任何行为修改。
 */
internal object DiggNetDiag {

    private const val FACTORY = "X.1JCt"

    /** Rx 订阅点。这些方法拿得到 ObservableEmitter，是「不投递结果」方案的候选点。 */
    private val RX_SUBSCRIBERS = listOf(
        "Y.AOSubscribeS1015S0100000_38" to listOf("subscribe\$0", "subscribe\$1", "subscribe\$2"),
        "Y.AOSubscribeS1001S0100000_15" to listOf("subscribe\$6", "subscribe\$7", "subscribe\$10"),
    )

    /**
     * 其它持有 `/aweme/v1/commit/item/digg/` 这个地址的类。
     * `X.1JCt` 那条路径引用了 IFamiliarRecommendService（朋友页推荐），
     * 很可能只是「朋友」流的点赞，主 feed 的点赞走的是别的入口，
     * 所以这里把这几个兄弟类也一并挂上，看看到底是谁在发请求。
     */
    private val SIBLING_CLASSES = listOf(
        "X.1JCx",
        "X.131Y",
        "X.14e4",
        "X.15KG",
    )

    /** 类太大就跳过，避免把几百个方法全挂上。 */
    private const val SIBLING_METHOD_LIMIT = 40

    /** 非 Rx 路径，只做记录。 */
    private val CALL_PATHS = listOf(
        "X.0ZFj" to "invokeSuspend",
        "X.0ZFk" to "invokeSuspend",
        "X.0ZFq" to "call",
        "X.1Iti" to "call",
        "Y.ACallableS319S0200000_38" to "call\$1",
    )

    fun install(module: XposedModule, loader: ClassLoader) {
        hookFactory(module, loader)
        hookRx(module, loader)
        hookCallPaths(module, loader)
        hookSiblings(module, loader)
    }

    // ---------------------------------------------------------------- 兄弟类：找真正的发起方

    private fun hookSiblings(module: XposedModule, loader: ClassLoader) {
        val hooked = mutableListOf<String>()
        for (className in SIBLING_CLASSES) {
            val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
            if (clazz == null) {
                module.log(Log.WARN, Diag.TAG, "DiggNetDiag: 找不到 $className")
                continue
            }
            val methods = clazz.declaredMethods
            if (methods.size > SIBLING_METHOD_LIMIT) {
                module.log(Log.WARN, Diag.TAG, "DiggNetDiag: $className 有 ${methods.size} 个方法，跳过")
                continue
            }
            for (method in methods) {
                runCatching {
                    module.hook(method).intercept { chain ->
                        Diag.log("sibling", "$className.${method.name} 被调用")
                        chain.proceed()
                    }
                    hooked += "$className.${method.name}"
                }
            }
        }
        module.log(
            Log.INFO,
            Diag.TAG,
            if (hooked.isEmpty()) "DiggNetDiag: 兄弟类一个都没挂上" else "DiggNetDiag sibling hooked: ${hooked.size} 个方法",
        )
    }

    // ---------------------------------------------------------------- 工厂层：看真实响应

    private fun hookFactory(module: XposedModule, loader: ClassLoader) {
        val clazz = runCatching { Class.forName(FACTORY, false, loader) }.getOrNull()
        if (clazz == null) {
            module.log(Log.WARN, Diag.TAG, "DiggNetDiag: 找不到 $FACTORY")
            return
        }

        val hooked = mutableListOf<String>()
        for (name in listOf("LIZ", "LIZIZ", "LIZJ")) {
            for (method in clazz.declaredMethods.filter { it.name == name }) {
                runCatching {
                    module.hook(method).intercept { chain ->
                        val result = runCatching { chain.proceed() }.getOrElse { throw it }
                        Diag.log("factory", "$name 返回 ${describe(result)}")
                        result
                    }
                    hooked += "$name/${method.parameterCount}"
                }
            }
        }

        module.log(
            Log.INFO,
            Diag.TAG,
            if (hooked.isEmpty()) "$FACTORY: 没有可挂的方法" else "DiggNetDiag factory hooked: ${hooked.joinToString("|")}",
        )
    }

    /** Pair(first=响应体, second=状态码) 是可读的关键信息，单独格式化出来。 */
    private fun describe(value: Any?): String {
        if (value == null) return "null"
        if (value is android.util.Pair<*, *>) {
            val body = value.first?.toString().orEmpty()
            val code = value.second
            return "Pair(first=${body.take(300)}, second=$code)"
        }
        return value.toString().take(300)
    }

    // ---------------------------------------------------------------- Rx 订阅点

    private fun hookRx(module: XposedModule, loader: ClassLoader) {
        val hooked = mutableListOf<String>()
        for ((className, methods) in RX_SUBSCRIBERS) {
            val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
            if (clazz == null) {
                module.log(Log.WARN, Diag.TAG, "DiggNetDiag: 找不到 $className")
                continue
            }
            for (method in clazz.declaredMethods.filter { it.name in methods }) {
                runCatching {
                    module.hook(method).intercept { chain ->
                        val emitter = chain.args.lastOrNull()
                        Diag.log("rx", "Rx 路径命中 ${clazz.simpleName}.${method.name}, emitter=${emitter?.javaClass?.simpleName}")
                        chain.proceed()
                    }
                    hooked += "${clazz.simpleName}.${method.name}"
                }
            }
        }
        module.log(
            Log.INFO,
            Diag.TAG,
            if (hooked.isEmpty()) "DiggNetDiag: Rx 订阅点一个都没挂上" else "DiggNetDiag rx hooked: ${hooked.joinToString("|")}",
        )
    }

    // ---------------------------------------------------------------- 非 Rx 路径

    private fun hookCallPaths(module: XposedModule, loader: ClassLoader) {
        val hooked = mutableListOf<String>()
        for ((className, methodName) in CALL_PATHS) {
            val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
            if (clazz == null) {
                module.log(Log.WARN, Diag.TAG, "DiggNetDiag: 找不到 $className")
                continue
            }
            for (method in clazz.declaredMethods.filter { it.name == methodName }) {
                runCatching {
                    module.hook(method).intercept { chain ->
                        Diag.log("call", "非 Rx 路径命中 ${clazz.simpleName}.${method.name}")
                        chain.proceed()
                    }
                    hooked += "${clazz.simpleName}.${method.name}"
                }
            }
        }
        module.log(
            Log.INFO,
            Diag.TAG,
            if (hooked.isEmpty()) "DiggNetDiag: 非 Rx 路径一个都没挂上" else "DiggNetDiag call hooked: ${hooked.joinToString("|")}",
        )
    }
}

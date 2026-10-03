package io.github.jjmj.douyinunlimit.xposed

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.jjmj.douyinunlimit.data.Prefs

/**
 * 模块入口。由 META-INF/xposed/java_init.list 声明。
 *
 * 只在抖音进程里干活；其它进程直接不挂任何 hook。
 */
class HookEntry : XposedModule() {

    private var prefs: SharedPreferences? = null

    /** onPackageReady 会重复触发，用它保证 hook 只装一遍。 */
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        prefs = runCatching { getRemotePreferences(Prefs.GROUP) }.getOrNull()
        Diag.log("onModuleLoaded 进程=${param.processName} prefs=${prefs != null}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        Diag.log("onPackageReady 触发，包名=${param.packageName}")
        if (param.packageName != TARGET_PACKAGE) return

        // 关键：onPackageReady 在同一个进程里会被调用多次（真机实测 2 次），
        // 不拦住就会把所有 hook 装两遍——不仅白白翻倍开销，还会破坏有状态的逻辑：
        // 比如双击检测，两个拦截器共用同一个时间戳，第一个写完后第二个立刻看到
        // 间隔 0ms，于是**每一次单击都被判成双击**。
        if (!installed.compareAndSet(false, true)) {
            Diag.log("onPackageReady 重复触发，已跳过（避免重复挂载）")
            return
        }

        val rules = RuleSource(prefs)

        // 详细调试日志的开关交给 Diag 查询（热路径，RuleSource 内部有 2 秒节流）
        Diag.setVerboseProvider { rules.debugLog() }
        Diag.log("debug", "详细调试日志 = ${rules.debugLog()}")

        // Application.onCreate 必定触发一次：既作为「模块确实注入了」的铁证，
        // 也用来在正确的时机初始化日志文件（onPackageReady 时 Application 还没创建）
        install("application") { hookApplicationReady(this) }

        install("toast") { ToastGuard.install(this, param.classLoader, rules) }
        install("im ban tips") { ImBanGuard.install(this, param.classLoader, rules) }
        install("send status") { SendStatusGuard.install(this, param.classLoader, rules) }
        install("text") { TextGuard.install(this, rules) }
        install("view") { ViewGuard.install(this, rules) }
        install("net") { NetGuard.install(this, param.classLoader, rules) }
        install("local digg") { LocalDigg.install(this, param.classLoader, rules) }
        install("click probe") { ClickProbe.install(this, rules) }

        Diag.log("onPackageReady 全部完成")
    }

    /**
     * 挂钩 Application.onCreate。这是注入成功的可靠信号：
     * 只要抖音进程起来了就一定会走一次，不受任何业务逻辑影响。
     */
    private fun hookApplicationReady(module: XposedModule) {
        val onCreate = runCatching {
            Application::class.java.getDeclaredMethod("onCreate")
        }.getOrNull() ?: return

        runCatching {
            module.hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    Diag.startSession("抖音进程 Application.onCreate")
                    Diag.log("模块已注入抖音进程，hook 全部就绪")
                }
                result
            }
        }
    }

    private inline fun install(name: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { Diag.log("install", "$name guard 已安装") }
            .onFailure { Diag.log("install", "$name guard 安装失败: $it") }
    }

    private companion object {
        const val TAG = "DouyinUnlimit"
        const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"
    }
}

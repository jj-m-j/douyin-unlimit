package io.github.jjmj.douyinunlimit.xposed

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

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        prefs = runCatching { getRemotePreferences(Prefs.GROUP) }.getOrNull()
        log(Log.INFO, TAG, "loaded, process=${param.processName}, prefs=${prefs != null}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return

        // hook 一次性全部装上，开关只影响拦截时的行为，改配置不用重启抖音
        val rules = RuleSource(prefs)

        install("toast") { ToastGuard.install(this, param.classLoader, rules) }
        install("im ban tips") { ImBanGuard.install(this, param.classLoader, rules) }
        install("send status") { SendStatusGuard.install(this, param.classLoader, rules) }
        install("view") { ViewGuard.install(this, rules) }
    }

    private inline fun install(name: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { log(Log.INFO, TAG, "$name guard installed") }
            .onFailure { log(Log.ERROR, TAG, "$name guard failed", it) }
    }

    private companion object {
        const val TAG = "DouyinUnlimit"
        const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"
    }
}

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

        val rules = RuleSource(prefs)
        runCatching { ToastGuard.install(this, param.classLoader, rules) }
            .onSuccess { log(Log.INFO, TAG, "toast guard installed") }
            .onFailure { log(Log.ERROR, TAG, "install failed", it) }
    }

    private companion object {
        const val TAG = "DouyinUnlimit"
        const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"
    }
}

package io.github.jjmj.douyinunlimit.data

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧：连接到 Xposed 框架的远程配置存储。
 *
 * 写入这里的值会被守护进程广播到所有已注入的目标进程，抖音不用重启即可生效。
 */
object SettingsBridge {
    private const val TAG = "SettingsBridge"

    var settings by mutableStateOf(ModuleSettings())
        private set

    var serviceConnected by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    fun register() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "service bound: ${service.frameworkName} ${service.frameworkVersion}")
                prefs = runCatching { service.getRemotePreferences(Prefs.GROUP) }.getOrNull()
                serviceConnected = prefs != null
                reload()
            }

            override fun onServiceDied(service: XposedService) {
                Log.i(TAG, "service died")
                prefs = null
                serviceConnected = false
            }
        })
    }

    private fun reload() {
        val p = prefs ?: return
        settings = ModuleSettings(
            blockToast = p.getBoolean(Prefs.KEY_BLOCK_TOAST, true),
            toastKeywords = Keywords.parse(p.getString(Prefs.KEY_TOAST_KEYWORDS, null)),
        )
    }

    fun setBlockToast(value: Boolean) {
        settings = settings.copy(blockToast = value)
        prefs?.edit()?.putBoolean(Prefs.KEY_BLOCK_TOAST, value)?.apply()
    }

    fun setToastKeywords(value: List<String>) {
        settings = settings.copy(toastKeywords = value)
        prefs?.edit()?.putString(Prefs.KEY_TOAST_KEYWORDS, Keywords.encode(value))?.apply()
    }

    fun resetToastKeywords() {
        setToastKeywords(Prefs.DEFAULT_TOAST_KEYWORDS)
    }
}

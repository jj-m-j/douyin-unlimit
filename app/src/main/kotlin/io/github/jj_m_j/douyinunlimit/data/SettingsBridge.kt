package io.github.jj_m_j.douyinunlimit.data

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
            hideTips = p.getBoolean(Prefs.KEY_HIDE_TIPS, true),
            hideText = p.getBoolean(Prefs.KEY_HIDE_TEXT, false),
            keywords = Keywords.parse(p.getString(Prefs.KEY_KEYWORDS, null)),
            stickyDigg = p.getBoolean(Prefs.KEY_STICKY_DIGG, true),
            debugLog = p.getBoolean(Prefs.KEY_DEBUG_LOG, false),
        )
    }

    fun setHideTips(value: Boolean) {
        settings = settings.copy(hideTips = value)
        put(Prefs.KEY_HIDE_TIPS, value)
    }

    fun setHideText(value: Boolean) {
        settings = settings.copy(hideText = value)
        put(Prefs.KEY_HIDE_TEXT, value)
    }

    fun setKeywords(value: List<String>) {
        settings = settings.copy(keywords = value)
        prefs?.edit()?.putString(Prefs.KEY_KEYWORDS, Keywords.encode(value))?.apply()
    }

    fun setStickyDigg(value: Boolean) {
        settings = settings.copy(stickyDigg = value)
        put(Prefs.KEY_STICKY_DIGG, value)
    }

    fun setDebugLog(value: Boolean) {
        settings = settings.copy(debugLog = value)
        put(Prefs.KEY_DEBUG_LOG, value)
    }

    private fun put(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }
}

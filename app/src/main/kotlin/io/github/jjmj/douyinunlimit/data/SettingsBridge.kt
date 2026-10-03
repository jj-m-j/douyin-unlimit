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
            hideImBanTips = p.getBoolean(Prefs.KEY_HIDE_IM_BAN_TIPS, true),
            hideSendStatus = p.getBoolean(Prefs.KEY_HIDE_SEND_STATUS, true),
            hideText = p.getBoolean(Prefs.KEY_HIDE_TEXT, true),
            hideViews = p.getBoolean(Prefs.KEY_HIDE_VIEWS, true),
            hideViewIds = ViewIds.parse(p.getString(Prefs.KEY_HIDE_VIEW_IDS, null)),
            blockDiggUpload = p.getBoolean(Prefs.KEY_STICKY_DIGG, true),
            debugLog = p.getBoolean(Prefs.KEY_DEBUG_LOG, false),
        )
    }

    fun setBlockToast(value: Boolean) {
        settings = settings.copy(blockToast = value)
        putBoolean(Prefs.KEY_BLOCK_TOAST, value)
    }

    fun setToastKeywords(value: List<String>) {
        settings = settings.copy(toastKeywords = value)
        putString(Prefs.KEY_TOAST_KEYWORDS, Keywords.encode(value))
    }

    fun resetToastKeywords() = setToastKeywords(Prefs.DEFAULT_TOAST_KEYWORDS)

    fun setHideImBanTips(value: Boolean) {
        settings = settings.copy(hideImBanTips = value)
        putBoolean(Prefs.KEY_HIDE_IM_BAN_TIPS, value)
    }

    fun setHideSendStatus(value: Boolean) {
        settings = settings.copy(hideSendStatus = value)
        putBoolean(Prefs.KEY_HIDE_SEND_STATUS, value)
    }

    fun setHideText(value: Boolean) {
        settings = settings.copy(hideText = value)
        putBoolean(Prefs.KEY_HIDE_TEXT, value)
    }

    fun setHideViews(value: Boolean) {
        settings = settings.copy(hideViews = value)
        putBoolean(Prefs.KEY_HIDE_VIEWS, value)
    }

    fun setHideViewIds(value: List<Int>) {
        settings = settings.copy(hideViewIds = value)
        putString(Prefs.KEY_HIDE_VIEW_IDS, ViewIds.encode(value))
    }

    fun resetHideViewIds() = setHideViewIds(ViewIds.DEFAULT)

    fun setDebugLog(value: Boolean) {
        settings = settings.copy(debugLog = value)
        putBoolean(Prefs.KEY_DEBUG_LOG, value)
    }

    fun setBlockDiggUpload(value: Boolean) {
        settings = settings.copy(blockDiggUpload = value)
        putBoolean(Prefs.KEY_STICKY_DIGG, value)
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun putString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }
}

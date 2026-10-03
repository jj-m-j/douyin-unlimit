package io.github.jjmj.douyinunlimit.xposed

import android.content.SharedPreferences
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.Prefs

/**
 * 在注入进程里读取模块配置。
 *
 * RemotePreferences 的底层是一个 volatile map，读取很便宜；关键词列表按原始字符串缓存，
 * 只有用户在模块 App 里改了配置才会重新解析。
 */
internal class RuleSource(private val prefs: SharedPreferences?) {

    private var cachedRaw: String? = null
    private var cached: List<String> = Prefs.DEFAULT_TOAST_KEYWORDS

    private fun keywords(): List<String> {
        val raw = runCatching { prefs?.getString(Prefs.KEY_TOAST_KEYWORDS, null) }.getOrNull()
        if (raw != cachedRaw) {
            cachedRaw = raw
            cached = Keywords.parse(raw)
        }
        return cached
    }

    fun shouldBlock(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!flag(Prefs.KEY_BLOCK_TOAST, true)) return false
        return keywords().any { text.contains(it) }
    }

    /** 隐藏消息 tab 顶部「消息发送功能已被禁止使用」横幅。 */
    fun hideImBanTips(): Boolean = flag(Prefs.KEY_HIDE_IM_BAN_TIPS, true)

    /** 更彻底：让抖音读到空的封禁信息。 */
    fun fakeNoBanInfo(): Boolean = flag(Prefs.KEY_FAKE_NO_BAN, false)

    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, default) }.getOrNull() ?: default
}

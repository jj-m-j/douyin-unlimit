package io.github.jjmj.douyinunlimit.xposed

import android.content.SharedPreferences
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.Prefs
import io.github.jjmj.douyinunlimit.data.ViewIds

/**
 * 在注入进程里读取模块配置。
 *
 * 界面元素相关的配置处在 setVisibility / addView 这类热路径上，所以不能每次都去读 prefs。
 * 这里用「最多每 500ms 真正同步一次」的方式换取热路径上的零分配判定：
 * 开关和 id 列表都缓存成 volatile 基本类型 / IntArray。
 */
internal class RuleSource(private val prefs: SharedPreferences?) {

    private var cachedRaw: String? = null
    private var cached: List<String> = Prefs.DEFAULT_TOAST_KEYWORDS

    // ---- 界面元素：热路径缓存 ----
    @Volatile
    private var hideViewsFlag: Boolean = true

    @Volatile
    private var ids: IntArray = ViewIds.DEFAULT.toIntArray()

    private var idsRaw: String? = null
    private var lastSync: Long = 0L

    private fun syncIfStale() {
        val now = System.nanoTime()
        if (now - lastSync < SYNC_INTERVAL_NS) return
        lastSync = now

        hideViewsFlag = flag(Prefs.KEY_HIDE_VIEWS, true)

        val raw = runCatching { prefs?.getString(Prefs.KEY_HIDE_VIEW_IDS, null) }.getOrNull()
        if (raw != idsRaw) {
            idsRaw = raw
            ids = ViewIds.parse(raw).toIntArray()
        }
    }

    // ---------------------------------------------------------------- 吐司

    private fun keywords(): List<String> {
        val raw = runCatching { prefs?.getString(Prefs.KEY_TOAST_KEYWORDS, null) }.getOrNull()
        if (raw != cachedRaw) {
            cachedRaw = raw
            cached = Keywords.parse(raw)
        }
        return cached
    }

    fun shouldBlockToast(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!flag(Prefs.KEY_BLOCK_TOAST, true)) return false
        return keywords().any { text.contains(it) }
    }

    // ---------------------------------------------------------------- 界面元素

    /** 隐藏消息 tab 顶部「消息发送功能已被禁止使用」横幅。 */
    fun hideImBanTips(): Boolean = flag(Prefs.KEY_HIDE_IM_BAN_TIPS, true)

    /** 隐藏聊天里的发送状态指示（红感叹号 + 「由于违反社区规定…」那段文字）。 */
    fun hideSendStatus(): Boolean = flag(Prefs.KEY_HIDE_SEND_STATUS, true)

    /** 「按 id 隐藏控件」总开关，走节流缓存。 */
    fun hideViewsEnabled(): Boolean {
        syncIfStale()
        return hideViewsFlag
    }

    /**
     * 这个 id 是否需要被强制隐藏。
     * 在热路径上调用：一次节流检查 + 一次线性扫描，无装箱无分配。
     */
    fun shouldHideView(viewId: Int): Boolean {
        if (viewId == 0) return false
        syncIfStale()
        if (!hideViewsFlag) return false
        val current = ids
        for (i in current.indices) {
            if (current[i] == viewId) return true
        }
        return false
    }

    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, default) }.getOrNull() ?: default

    private companion object {
        /** 500ms */
        const val SYNC_INTERVAL_NS = 500_000_000L
    }
}

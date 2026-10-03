package io.github.jjmj.douyinunlimit.xposed

import android.content.SharedPreferences
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.Prefs
import io.github.jjmj.douyinunlimit.data.ViewIds

/**
 * 在注入进程里读取模块配置。
 *
 * RemotePreferences 的底层是一个 volatile map，读取很便宜。列表类配置按原始字符串缓存，
 * 只有用户在模块 App 里改了才会重新解析——view id 的判定在 setVisibility 热路径上，
 * 所以额外摊平成 IntArray，避免装箱。
 */
internal class RuleSource(private val prefs: SharedPreferences?) {

    private var cachedRaw: String? = null
    private var cached: List<String> = Prefs.DEFAULT_TOAST_KEYWORDS

    private var idsRaw: String? = null

    @Volatile
    private var ids: IntArray = ViewIds.DEFAULT.toIntArray()

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

    /** 隐藏消息 tab 顶部「消息发送功能已被禁止使用」横幅。 */
    fun hideImBanTips(): Boolean = flag(Prefs.KEY_HIDE_IM_BAN_TIPS, true)

    /** 按控件 id 隐藏界面元素。 */
    fun hideViews(): Boolean = flag(Prefs.KEY_HIDE_VIEWS, true)

    /**
     * 这个 id 是否需要被强制隐藏。
     * 在 setVisibility 的热路径上调用，只做一次线性扫描，无装箱无分配。
     */
    fun shouldHideView(viewId: Int): Boolean {
        if (viewId == 0) return false
        val current = ids
        for (i in current.indices) {
            if (current[i] == viewId) return true
        }
        return false
    }

    private fun refreshIdsIfNeeded() {
        val raw = runCatching { prefs?.getString(Prefs.KEY_HIDE_VIEW_IDS, null) }.getOrNull()
        if (raw != idsRaw) {
            idsRaw = raw
            ids = ViewIds.parse(raw).toIntArray()
        }
    }

    /** 配置是随时可改的，热路径上先做一次廉价的刷新检查。 */
    fun syncViewIds() = refreshIdsIfNeeded()

    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, default) }.getOrNull() ?: default
}

package io.github.jjmj.douyinunlimit.xposed

import android.content.SharedPreferences
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.Prefs
import io.github.jjmj.douyinunlimit.data.ViewIds
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在注入进程里读取模块配置。
 *
 * ## 功耗设计
 *
 * 这些配置会被 setVisibility / setText / addView / inflate 这类**每秒调用成千上万次**的
 * 热路径读取，所以热路径上**不能有任何 I/O、时间调用或对象分配**：
 *
 *  - 所有配置都缓存成 volatile 基本类型 / 数组，读取只是纯内存访问
 *  - 用「调用计数器」而不是 `System.nanoTime()` 做节流（时间调用远贵于计数器自增）
 *  - 每 1024 次热路径调用才真正去读一次 SharedPreferences
 *  - 命中判定是纯数组扫描，无装箱、无字符串拼接
 *
 * 于是未命中时每次调用只有：读一个 volatile + 计数器自增 + 一次数组扫描。
 */
internal class RuleSource(private val prefs: SharedPreferences?) {

    // ---- 热路径缓存：全是 volatile，读取直接命中 CPU 缓存 ----

    @Volatile
    private var hideViewsFlag: Boolean = true

    @Volatile
    private var hideTextFlag: Boolean = true

    @Volatile
    private var blockDiggFlag: Boolean = true

    @Volatile
    private var ids: IntArray = ViewIds.DEFAULT.toIntArray()

    @Volatile
    private var keywords: Array<String> = Prefs.DEFAULT_TOAST_KEYWORDS.toTypedArray()

    private var idsRaw: String? = null
    private var keywordsRaw: String? = null

    private val tick = AtomicInteger(0)

    init {
        sync()
    }

    private fun sync() {
        hideViewsFlag = flag(Prefs.KEY_HIDE_VIEWS, true)
        hideTextFlag = flag(Prefs.KEY_HIDE_TEXT, true)
        blockDiggFlag = flag(Prefs.KEY_STICKY_DIGG, true)

        val idRaw = runCatching { prefs?.getString(Prefs.KEY_HIDE_VIEW_IDS, null) }.getOrNull()
        if (idRaw != idsRaw) {
            idsRaw = idRaw
            ids = ViewIds.parse(idRaw).toIntArray()
        }

        val keywordRaw = runCatching { prefs?.getString(Prefs.KEY_TOAST_KEYWORDS, null) }.getOrNull()
        if (keywordRaw != keywordsRaw) {
            keywordsRaw = keywordRaw
            keywords = Keywords.parse(keywordRaw).toTypedArray()
        }
    }

    /** 热路径节流：每 1024 次调用真正读一次配置。 */
    private fun tickSync() {
        if (tick.incrementAndGet() and TICK_MASK == 0) sync()
    }

    // ---------------------------------------------------------------- 界面元素

    /** 「按 id 隐藏控件」总开关。热路径调用，只做一次计数器自增。 */
    fun hideViewsEnabled(): Boolean {
        tickSync()
        return hideViewsFlag
    }

    /** 调用前必须先过 [hideViewsEnabled]（它负责节流同步），这里只做纯扫描。 */
    fun shouldHideView(viewId: Int): Boolean {
        if (viewId == 0) return false
        val current = ids
        for (i in current.indices) {
            if (current[i] == viewId) return true
        }
        return false
    }

    /** 按关键词隐藏文字。同样走缓存 + 纯扫描。 */
    fun shouldHideText(text: CharSequence): Boolean {
        if (text.length < MIN_KEYWORD_LENGTH) return false
        tickSync()
        if (!hideTextFlag) return false
        val current = keywords
        for (i in current.indices) {
            val keyword = current[i]
            if (keyword.length <= text.length && text.contains(keyword)) return true
        }
        return false
    }

    // ---------------------------------------------------------------- 吐司

    fun shouldBlockToast(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!flag(Prefs.KEY_BLOCK_TOAST, true)) return false
        // 吐司调用频率很低，不走热路径缓存
        val list = Keywords.parse(
            runCatching { prefs?.getString(Prefs.KEY_TOAST_KEYWORDS, null) }.getOrNull(),
        )
        return list.any { text.contains(it) }
    }

    // ---------------------------------------------------------------- 其它

    /** 隐藏消息 tab 顶部封禁横幅。 */
    fun hideImBanTips(): Boolean = flag(Prefs.KEY_HIDE_IM_BAN_TIPS, true)

    /** 隐藏聊天里的发送状态指示。 */
    fun hideSendStatus(): Boolean = flag(Prefs.KEY_HIDE_SEND_STATUS, true)

    /** 拦截点赞请求上传。 */
    fun blockDiggUpload(): Boolean = blockDiggFlag

    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, default) }.getOrNull() ?: default

    private companion object {
        /** 2^10 - 1 */
        const val TICK_MASK = 0x3FF
        const val MIN_KEYWORD_LENGTH = 2
    }
}

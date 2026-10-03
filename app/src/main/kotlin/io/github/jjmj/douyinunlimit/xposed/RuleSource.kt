package io.github.jjmj.douyinunlimit.xposed

import android.content.SharedPreferences
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.Prefs

/**
 * 在注入进程里读取模块配置。
 *
 * ## 两套词表，刻意分开
 *
 * | 用途 | 来源 | 为什么 |
 * |---|---|---|
 * | 吐司 + 常驻文字 | **内置**限制词（[Prefs.BUILTIN_BLOCK_WORDS]） | 「别提示我被限制了」默认开着，必须开箱即用 |
 * | 文字兜底 | **用户自己填**（默认空） | 用户写的词边界不可预期，单独一个开关让他自己权衡 |
 *
 * v1.13 让两者共用同一份用户词表，结果是「关键词清空 -> 吐司拦截也一起失效」。
 *
 * ## 功耗设计
 *
 * `TextView.setText` / `View.setVisibility` 这类方法每秒会被调用成千上万次，
 * 所以热路径上**不能有任何 I/O、时间调用或对象分配**：
 *
 *  - 配置全部缓存成 volatile 基本类型 / 数组，读取只是纯内存访问
 *  - 节流用**调用计数器**而不是 `System.nanoTime()`（时间调用比自增贵得多）
 *  - 计数器刻意不用 AtomicInteger：热点上 CAS 会跨线程争抢，而这里算错一两次
 *    只意味着「晚一点同步」，没有任何正确性影响，用普通 Int 最便宜
 *  - 每 1024 次热路径调用才真正读一次 SharedPreferences（[tick]）
 *  - 命中判定是纯数组扫描，无装箱、无字符串拼接
 */
internal class RuleSource(private val prefs: SharedPreferences?) {

    // ---------------------------------------------------------------- 缓存

    @Volatile
    private var hideTipsFlag: Boolean = true

    @Volatile
    private var hideTextFlag: Boolean = false

    @Volatile
    private var stickyDiggFlag: Boolean = true

    @Volatile
    private var debugFlag: Boolean = false

    /** 用户自己填的关键词。没有预置内容。 */
    @Volatile
    private var keywords: Array<String> = EMPTY

    /** 上一次同步时读到的原文，用来判断「配置没变就不必重新解析」。 */
    private var keywordsRaw: String? = null

    /** 非原子计数器，见类注释。 */
    private var tick: Int = 0

    private var lastDebugCheck: Long = 0L

    init {
        sync()
    }

    private fun sync() {
        hideTipsFlag = flag(Prefs.KEY_HIDE_TIPS, true)
        hideTextFlag = flag(Prefs.KEY_HIDE_TEXT, false)
        stickyDiggFlag = flag(Prefs.KEY_STICKY_DIGG, true)

        val raw = runCatching { prefs?.getString(Prefs.KEY_KEYWORDS, null) }.getOrNull()
        if (raw != keywordsRaw) {
            keywordsRaw = raw
            keywords = Keywords.parse(raw).toTypedArray()
        }
    }

    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, default) }.getOrNull() ?: default

    // ---------------------------------------------------------------- 热路径

    /**
     * 热路径节流同步：每 1024 次调用真正读一次配置。
     *
     * 由 setText 这类必然高频的入口调用一次即可 —— 它同时负责让
     * [hideTips] / [keywordHiding] 这些缓存标志保持新鲜。
     */
    fun tick() {
        if (++tick >= TICK_LIMIT) {
            tick = 0
            sync()
        }
    }

    /** 隐藏一切限制提示（吐司 / 横幅 / 发送失败图标 / 服务端下发的限制文案）。 */
    fun hideTips(): Boolean = hideTipsFlag

    /** 关键词拦截是否生效。没有词表时它本来就是空转的。 */
    fun keywordHiding(): Boolean = hideTextFlag && keywords.isNotEmpty()

    /** 纯扫描：是不是内置限制文案。 */
    fun matchesBuiltin(text: CharSequence): Boolean = matches(Prefs.BUILTIN_BLOCK_WORDS, text)

    /** 纯扫描：是不是用户自己填的关键词。 */
    fun matchesKeyword(text: CharSequence): Boolean = matches(keywords, text)

    /**
     * 吐司文案判定。
     *
     * 两个来源，各自受自己的开关控制：
     *  - 内置限制词 —— 「别提示我被限制了」（默认开）
     *  - 用户关键词 —— 「关键词拦截」（默认关）
     */
    fun shouldBlockToast(text: String): Boolean {
        if (text.isEmpty()) return false
        if (hideTipsFlag && matchesBuiltin(text)) return true
        return keywordHiding() && matchesKeyword(text)
    }

    private fun matches(words: Array<String>, text: CharSequence): Boolean {
        if (text.length < MIN_KEYWORD_LENGTH) return false
        for (i in words.indices) {
            val word = words[i]
            if (word.length <= text.length && text.contains(word)) return true
        }
        return false
    }

    // ---------------------------------------------------------------- 冷路径

    /** 点赞被驳回后不回滚。 */
    fun stickyDigg(): Boolean = stickyDiggFlag

    /**
     * 「记录详细日志」开关。关闭时 Diag 一行都不写。
     * 点击探针等高频路径会问它，所以做 2 秒节流，避免每次都去读 prefs。
     */
    fun debugLog(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDebugCheck >= DEBUG_CHECK_INTERVAL_MS) {
            lastDebugCheck = now
            debugFlag = flag(Prefs.KEY_DEBUG_LOG, false)
        }
        return debugFlag
    }

    private companion object {
        /** 2^10 */
        const val TICK_LIMIT = 1024

        /** 比最短的词还短的文字不可能命中，直接跳过扫描。 */
        const val MIN_KEYWORD_LENGTH = 2

        const val DEBUG_CHECK_INTERVAL_MS = 2000L

        val EMPTY = emptyArray<String>()
    }
}

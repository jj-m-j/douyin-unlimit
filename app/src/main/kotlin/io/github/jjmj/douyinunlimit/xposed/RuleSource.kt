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
 * | 吐司拦截 | **内置**限制词（[Prefs.TOAST_BLOCK_WORDS]） | 开关一开就该生效，不该要求用户先维护一份词表 |
 * | 文字兜底 | **用户自己填**（默认空） | 按文字匹配有误伤正常内容的风险，不能预置 |
 *
 * v1.13 让两者共用同一份用户词表，结果是「关键词清空 -> 吐司拦截也一起失效」。
 * 拆开之后两边各自独立。
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
 *  - 每 1024 次热路径调用才真正读一次 SharedPreferences
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
     * 文字类拦截的开关。顺带做节流同步。
     *
     * 注意这里**不能**在开关为 false 时提前返回：那样一旦关掉就再也不会同步，
     * 用户重新打开开关也永远不会生效。
     */
    fun textHidingOn(): Boolean {
        if (++tick >= TICK_LIMIT) {
            tick = 0
            sync()
        }
        return hideTextFlag && keywords.isNotEmpty()
    }

    /** 纯扫描，调用前必须已经过 [textHidingOn]。 */
    fun shouldHideText(text: CharSequence): Boolean {
        if (text.length < MIN_KEYWORD_LENGTH) return false
        val current = keywords
        for (i in current.indices) {
            val keyword = current[i]
            if (keyword.length <= text.length && text.contains(keyword)) return true
        }
        return false
    }

    // ---------------------------------------------------------------- 冷路径

    /**
     * 吐司文案判定，用**内置**限制词，和用户的词表无关。
     *
     * v1.13 这里每次调用都 `Keywords.parse(prefs.getString(...))` ——
     * 也就是每条吐司都做一次字符串 split + List 分配 + 逐项 trim，纯浪费。
     * 现在走常量数组。
     */
    fun shouldBlockToast(text: String): Boolean {
        if (text.isEmpty()) return false
        val current = Prefs.TOAST_BLOCK_WORDS
        for (i in current.indices) {
            if (text.contains(current[i])) return true
        }
        return false
    }

    /** 隐藏一切限制提示（吐司 / 消息页横幅 / 聊天发送状态）。 */
    fun hideTips(): Boolean = hideTipsFlag

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

        /** 比最短的关键词还短的文字不可能命中，直接跳过扫描。 */
        const val MIN_KEYWORD_LENGTH = 2

        const val DEBUG_CHECK_INTERVAL_MS = 2000L

        val EMPTY = emptyArray<String>()
    }
}

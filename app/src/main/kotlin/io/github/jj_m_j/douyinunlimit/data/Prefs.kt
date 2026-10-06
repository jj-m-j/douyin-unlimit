package io.github.jj_m_j.douyinunlimit.data

/**
 * 模块 App 与注入进程共用的配置约定。
 * 键值通过 libxposed 的远程 SharedPreferences 传输，group 名必须两边一致。
 *
 * ## 四个开关
 *
 * | 开关 | 默认 | 覆盖 |
 * |---|---|---|
 * | [KEY_HIDE_TIPS] | 开 | 吐司 + 消息页横幅 + 聊天发送状态 + **服务端下发的限制文案** |
 * | [KEY_HIDE_TEXT] | 关 | 用户自己填的关键词（额外兜底） |
 * | [KEY_STICKY_DIGG] | 开 | 点赞被驳回后不回滚 |
 * | [KEY_DEBUG_LOG] | 关 | 关闭时一行日志都不写 |
 */
object Prefs {
    const val GROUP = "settings"

    /** 隐藏一切限制提示。默认开。 */
    const val KEY_HIDE_TIPS = "hide_tips"

    /** 按用户自定义关键词抹掉文字。**默认关**。 */
    const val KEY_HIDE_TEXT = "hide_text"

    /** 用户自定义关键词，每行一条。**默认空**，由用户自己填。 */
    const val KEY_KEYWORDS = "keywords"

    /** 点赞被服务端驳回后，不回滚本地的已赞状态。默认开。 */
    const val KEY_STICKY_DIGG = "sticky_digg"

    /** 记录详细日志。默认关，且关闭时一行日志都不写。 */
    const val KEY_DEBUG_LOG = "debug_log"

    /**
     * **内置**限制词，不可编辑。
     *
     * 很多限制文案是**服务端下发**的（例如「由于违反社区规定，你的私信功能暂被封禁」），
     * 既不在 dex 字符串里也不在资源表里，没有类或 id 可以挂 —— 只能按运行时文字内容判定。
     * 这些是抖音惯用的固定措辞，跨版本基本不变，所以内置一份。
     *
     * 和用户的 [KEY_KEYWORDS] **刻意分开**：
     *
     *  - 内置词服务于「别提示我被限制了」。它默认开着，所以必须开箱即用，
     *    不能要求用户先去维护一份词表。
     *  - 用户词表服务于「关键词兜底」，默认关。用户自己写的词边界不可预期，
     *    单独一个开关让他能自己权衡。
     *
     * v1.13 让两者共用一份用户词表，结果是「关键词清空 -> 吐司拦截也一起失效」。
     */
    val BUILTIN_BLOCK_WORDS = arrayOf(
        "封禁",
        "已被限制",
        "被限制",
        "功能已被",
        "无法使用该功能",
        "涉嫌违规",
        "禁止使用",
        "已被禁止",
        "违反社区规定",
    )
}

object Keywords {
    private const val SEPARATOR = "\n"

    /** 每行一个关键词。空或 null 都是「没有关键词」。 */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun encode(list: List<String>): String =
        list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(SEPARATOR)
}

package io.github.jjmj.douyinunlimit.data

/**
 * 模块 App 与注入进程共用的配置约定。
 * 键值通过 libxposed 的远程 SharedPreferences 传输，group 名必须两边一致。
 *
 * ## 为什么只有四个开关
 *
 * v1.13 有七个开关，其中四个其实在描述同一件事（「抖音在告诉我我被限制了」），
 * 只是实现落在代码的不同层：
 *
 *   弹窗吐司   -> DUX Toast 体系
 *   消息页横幅 -> ChatBanTipsLogic
 *   聊天红叹号 -> StatusIconWithText
 *   散落文案   -> 按运行时文字关键词匹配
 *
 * 用户想消掉的是「现象」，不是「实现」。前三者按固定的类/形状精准拦截，零误伤风险，
 * 合成一个开关；第四个靠文字匹配、有误伤可能，所以单独留着。
 */
object Prefs {
    const val GROUP = "settings"

    /** 隐藏一切限制提示：吐司 + 消息页横幅 + 聊天发送状态。默认开。 */
    const val KEY_HIDE_TIPS = "hide_tips"

    /** 按关键词抹掉页面上的文字。**默认关**（见 [KEY_KEYWORDS]）。 */
    const val KEY_HIDE_TEXT = "hide_text"

    /**
     * 关键词表，每行一条。
     *
     * **没有默认词表**：这个功能默认关闭，也不预置任何关键词。
     * 按文字匹配天然有误伤正常内容的风险，预置词表等于替用户做了他没要求的决定。
     * 由用户自己填他想抹掉的那几句。
     */
    const val KEY_KEYWORDS = "keywords"

    /** 点赞被服务端驳回后，不回滚本地的已赞状态。默认开。 */
    const val KEY_STICKY_DIGG = "sticky_digg"

    /** 记录详细日志。默认关，且关闭时一行日志都不写。 */
    const val KEY_DEBUG_LOG = "debug_log"

    /**
     * 吐司层内置的限制词，**不可编辑**。
     *
     * 刻意和用户的 [KEY_KEYWORDS] 分开：吐司拦截（「别提示我被限制了」）开关一开就该生效，
     * 不该要求用户先去维护一份词表。它只作用于弹窗吐司的文案，误伤面很小。
     */
    val TOAST_BLOCK_WORDS = arrayOf(
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

package io.github.jjmj.douyinunlimit.data

/**
 * 模块 App 与注入进程共用的配置约定。
 * 键值通过 libxposed 的远程 SharedPreferences 传输，group 名必须两边一致。
 *
 * ## 为什么只有四个开关
 *
 * v1.13 之前有七个开关，其中四个其实在描述同一件事（「抖音在告诉我我被限制了」），
 * 只是实现落在代码的不同层：
 *
 *   弹窗吐司   -> DUX Toast 体系
 *   消息页横幅 -> ChatBanTipsLogic
 *   聊天红叹号 -> StatusIconWithText
 *   散落文案   -> 按运行时文字关键词匹配
 *
 * 用户想消掉的是「现象」，不是「实现」。前三者按固定的类/接口精准拦截，零误伤风险，
 * 合成一个开关；第四个靠文字匹配、有误伤可能，所以单独留着让用户能关掉。
 */
object Prefs {
    const val GROUP = "settings"

    /** 隐藏一切限制提示：吐司 + 消息页横幅 + 聊天发送状态。 */
    const val KEY_HIDE_TIPS = "hide_tips"

    /** 按关键词抹掉页面上的文字（兜底层，有误伤风险）。 */
    const val KEY_HIDE_TEXT = "hide_text"

    /** 关键词表，吐司和文字共用。每行一条。 */
    const val KEY_KEYWORDS = "keywords"

    /** 点赞被服务端驳回后，不回滚本地的已赞状态。 */
    const val KEY_STICKY_DIGG = "sticky_digg"

    /** 详细调试日志。 */
    const val KEY_DEBUG_LOG = "debug_log"

    /**
     * 旧键名。v1.13 之前「屏蔽限制类弹窗」和「抹掉带关键词的文字」各自持有一份词表，
     * 现在合并成一份。读一次旧值，避免升级后用户自己维护的词表被重置成默认。
     */
    const val LEGACY_KEY_KEYWORDS = "toast_keywords"

    /** 默认关键词：命中任意一条即隐藏该文字 / 静默该吐司。 */
    val DEFAULT_KEYWORDS = listOf(
        "封禁",
        "已被限制",
        "被限制",
        "功能已被",
        "无法使用该功能",
        "涉嫌违规",
    )
}

object Keywords {
    private const val SEPARATOR = "\n"

    /** 每行一个关键词。null 表示从未写过配置，回退到默认值。 */
    fun parse(raw: String?): List<String> {
        if (raw == null) return Prefs.DEFAULT_KEYWORDS
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun encode(list: List<String>): String =
        list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(SEPARATOR)
}

package io.github.jjmj.douyinunlimit.data

/**
 * 模块 App 与注入进程共用的配置约定。
 * 键值通过 libxposed 的远程 SharedPreferences 传输，group 名必须两边一致。
 */
object Prefs {
    const val GROUP = "settings"

    // 吐司
    const val KEY_BLOCK_TOAST = "block_toast"
    const val KEY_TOAST_KEYWORDS = "toast_keywords"

    // 界面元素
    const val KEY_HIDE_IM_BAN_TIPS = "hide_im_ban_tips"
    const val KEY_HIDE_VIEWS = "hide_views"
    const val KEY_HIDE_VIEW_IDS = "hide_view_ids"

    /** 默认拦截关键词：命中任意一条即静默该吐司。 */
    val DEFAULT_TOAST_KEYWORDS = listOf(
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
        if (raw == null) return Prefs.DEFAULT_TOAST_KEYWORDS
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun encode(list: List<String>): String =
        list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(SEPARATOR)
}

/**
 * 要隐藏的控件 id。
 *
 * 这些 id 来自 Layout Inspect，是 aapt 在打包时分配的，**抖音升级后可能变化**，
 * 所以做成可编辑列表。输入接受 `0x7f0ab151`、`7f0ab151`（按十六进制解析）或十进制。
 */
object ViewIds {
    /** 0x7f0ab151: 聊天里的发送状态图标（红感叹号，ImImageView） */
    const val SEND_STATUS_ICON = 0x7f0ab151

    /** 0x7f0aa9d7: 状态文字容器（DrawChildOptEllipsizeLayout，「由于违反社区规定…」那行） */
    const val SEND_STATUS_TEXT = 0x7f0aa9d7

    val DEFAULT: List<Int> = listOf(SEND_STATUS_ICON, SEND_STATUS_TEXT)

    private const val SEPARATOR = "\n"

    fun parse(raw: String?): List<Int> {
        if (raw == null) return DEFAULT
        return raw.split(SEPARATOR).mapNotNull { line ->
            val text = line.trim()
            if (text.isEmpty()) return@mapNotNull null
            val body = text.removePrefix("0x").removePrefix("0X")
            body.toLongOrNull(16)?.toInt() ?: text.toIntOrNull()
        }.distinct()
    }

    fun encode(ids: Collection<Int>): String =
        ids.distinct().joinToString(SEPARATOR) { "0x%08x".format(it) }
}

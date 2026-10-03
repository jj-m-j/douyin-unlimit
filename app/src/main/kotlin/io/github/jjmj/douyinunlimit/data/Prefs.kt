package io.github.jjmj.douyinunlimit.data

/**
 * 模块 App 与注入进程共用的配置约定。
 * 键值通过 libxposed 的远程 SharedPreferences 传输，group 名必须两边一致。
 */
object Prefs {
    const val GROUP = "settings"

    const val KEY_BLOCK_TOAST = "block_toast"
    const val KEY_TOAST_KEYWORDS = "toast_keywords"

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

package io.github.jjmj.douyinunlimit.data

/**
 * 模块的全部可配置项。字段顺序即设置页的展示顺序。
 *
 * 「按控件 id 隐藏」在 v1.14 被移除：它的默认列表本来就是空的，也就是说默认状态下
 * 它什么都不做，只是一个需要用户先会用 Layout Inspect 才能用起来的占位开关。
 * 它能覆盖的场景，[keywords] 那条按文字匹配的路已经覆盖了，而且不依赖打包时分配的
 * 资源 id（抖音升级就会变）。
 */
data class ModuleSettings(
    val hideTips: Boolean = true,
    val hideText: Boolean = true,
    val keywords: List<String> = Prefs.DEFAULT_KEYWORDS,
    val stickyDigg: Boolean = true,
    val debugLog: Boolean = false,
)

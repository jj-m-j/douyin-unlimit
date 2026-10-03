package io.github.jjmj.douyinunlimit.data

/**
 * 模块的全部可配置项。字段顺序即设置页的展示顺序。
 *
 * 两处刻意的「默认不生效」：
 *
 *  - [hideText] 默认**关**，而且 [keywords] 默认**为空**。按文字匹配天然有误伤正常内容的
 *    风险，不该在用户没要求的时候替他决定要抹掉哪些字。
 *  - [debugLog] 默认**关**，关闭时一行日志都不写（见 Diag）。
 *
 * 「按控件 id 隐藏」在 v1.14 被移除：`ViewIds.DEFAULT` 本来就是空列表，
 * 也就是说默认状态下它什么都不做，只是一个需要用户先会用 Layout Inspect 才能用起来的
 * 占位开关。它背后的能力（把某个控件彻底压住）后来以**按实例登记**的形式回来了 ——
 * 只压自己抓到的那个 View，不会像资源 id 黑名单那样误伤复用同一 id 的其它界面。
 */
data class ModuleSettings(
    val hideTips: Boolean = true,
    val hideText: Boolean = false,
    val keywords: List<String> = emptyList(),
    val stickyDigg: Boolean = true,
    val debugLog: Boolean = false,
)

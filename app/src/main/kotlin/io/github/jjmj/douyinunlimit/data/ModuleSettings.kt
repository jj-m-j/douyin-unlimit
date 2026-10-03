package io.github.jjmj.douyinunlimit.data

data class ModuleSettings(
    val blockToast: Boolean = true,
    val toastKeywords: List<String> = Prefs.DEFAULT_TOAST_KEYWORDS,
    val hideImBanTips: Boolean = true,
    val fakeNoBanInfo: Boolean = false,
)

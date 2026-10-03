package io.github.jjmj.douyinunlimit.data

data class ModuleSettings(
    val blockToast: Boolean = true,
    val toastKeywords: List<String> = Prefs.DEFAULT_TOAST_KEYWORDS,
    val hideImBanTips: Boolean = true,
    val hideSendStatus: Boolean = true,
    val hideText: Boolean = true,
    val hideViews: Boolean = true,
    val hideViewIds: List<Int> = ViewIds.DEFAULT,
    val blockDiggUpload: Boolean = true,
    val debugLog: Boolean = false,
)

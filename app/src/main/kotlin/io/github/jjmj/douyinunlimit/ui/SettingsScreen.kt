package io.github.jjmj.douyinunlimit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.SettingsBridge
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private const val VERSION = "1.0"

private const val REPO_URL = "https://github.com/jj-m-j/douyin-unlimit"
private const val REPO_LABEL = "github.com/jj-m-j/douyin-unlimit"

/** 关键词改完停顿多久后自动落盘。 */
private const val AUTOSAVE_DELAY_MS = 600L

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 卡片统一的内缩与块间距，Miuix 规范是横向 12dp。 */
private fun Modifier.cardInset() = this
    .padding(horizontal = 12.dp)
    .padding(bottom = 12.dp)

/**
 * 分组按「你看到什么被干掉」来分，不是按代码里的 Guard 分。
 *
 * v1.13 有七个开关，其中四个在描述同一件事——「抖音在告诉我我被限制了」，
 * 只是实现落在不同层（吐司 / 横幅 / 红叹号 / 散落文案）。用户想消掉的是现象不是实现，
 * 所以前三者（按固定类精准拦截、零误伤）合成一个开关；第四个靠文字匹配、可能误伤
 * 正常内容，单独留着让用户能单独关掉。
 */
@Composable
fun SettingsScreen() {
    val settings = SettingsBridge.settings
    val connected = SettingsBridge.serviceConnected
    val context = LocalContext.current

    // 必须放在 LazyColumn 外面：item {} 各自是独立作用域
    var keywordDraft by remember(settings.keywords) {
        mutableStateOf(TextFieldValue(Keywords.encode(settings.keywords)))
    }

    // 自动保存：用户停止输入 600ms 后写一次，省掉一个「保存」按钮。
    // 写成比较后再存，避免刚进入页面 / 刚同步完就把同样的内容再写一遍。
    LaunchedEffect(keywordDraft.text) {
        if (keywordDraft.text == Keywords.encode(settings.keywords)) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        SettingsBridge.setKeywords(Keywords.parse(keywordDraft.text))
    }

    Scaffold(
        topBar = {
            TopAppBar(title = "抖音伪装", subtitle = "v$VERSION")
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            // ---------------------------------------------------------- 连接状态
            item(key = "status") {
                SmallTitle(text = "状态")
                Card(modifier = Modifier.cardInset()) {
                    BasicComponent(
                        title = if (connected) "已连接 LSPosed" else "未连接 LSPosed",
                        // 连接上之后不再显示副标题 —— 传 null 而不是空串，
                        // 否则会在卡片里留出一条空行（summary 是可空的）
                        summary = if (connected) {
                            null
                        } else {
                            "请在 LSPosed 里启用本模块，再重启本应用"
                        },
                    )
                }
            }

            // ---------------------------------------------------------- 限制提示
            item(key = "tips") {
                SmallTitle(text = "限制提示")
                Card(modifier = Modifier.cardInset()) {
                    SwitchPreference(
                        checked = settings.hideTips,
                        onCheckedChange = { SettingsBridge.setHideTips(it) },
                        title = "别提示我被限制了",
                        summary = "干掉弹窗吐司、消息页顶部横幅、红叹号。",
                    )
                }
            }

            // ---------------------------------------------------------- 关键词拦截
            item(key = "keywords") {
                SmallTitle(text = "关键词")
                Card(modifier = Modifier.cardInset()) {
                    SwitchPreference(
                        checked = settings.hideText,
                        onCheckedChange = { SettingsBridge.setHideText(it) },
                        title = "关键词拦截",
                        summary = "拦截含有关键词的吐司或者文案。",
                    )
                }
                // 开关切换时让输入框带进出动画，而不是突然出现 / 消失
                AnimatedVisibility(
                    visible = settings.hideText,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    TextField(
                        value = keywordDraft,
                        onValueChange = { keywordDraft = it },
                        label = "填要抹掉的字，每行一条（自动保存）",
                        useLabelAsPlaceholder = true,
                        singleLine = false,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    )
                }
            }

            // ---------------------------------------------------------- 点赞
            item(key = "digg") {
                SmallTitle(text = "点赞")
                Card(modifier = Modifier.cardInset()) {
                    SwitchPreference(
                        checked = settings.stickyDigg,
                        onCheckedChange = { SettingsBridge.setStickyDigg(it) },
                        title = "假装点赞成功了",
                    )
                }
            }

            // ---------------------------------------------------------- 调试
            item(key = "debug") {
                SmallTitle(text = "调试")
                Card(modifier = Modifier.cardInset()) {
                    SwitchPreference(
                        checked = settings.debugLog,
                        onCheckedChange = { SettingsBridge.setDebugLog(it) },
                        title = "记录详细日志",
                        summary = "记录每次点击的控件和祖先链、点赞链路的每一步，平时关掉省电",
                    )
                }
            }

            // ---------------------------------------------------------- 关于
            item(key = "about") {
                SmallTitle(text = "关于")
                Card(modifier = Modifier.cardInset()) {
                    ArrowPreference(
                        title = "GitHub 仓库",
                        summary = REPO_LABEL,
                        onClick = { openUrl(context, REPO_URL) },
                    )
                }
            }
        }
    }
}

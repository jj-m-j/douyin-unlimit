package io.github.jjmj.douyinunlimit.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.jjmj.douyinunlimit.data.Keywords
import io.github.jjmj.douyinunlimit.data.SettingsBridge
import io.github.jjmj.douyinunlimit.data.ViewIds
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private const val VERSION = "1.13.0"

/** 卡片统一的内缩与块间距，Miuix 规范是横向 12dp。 */
private fun Modifier.cardInset() = this
    .padding(horizontal = 12.dp)
    .padding(bottom = 12.dp)

/**
 * 分组按「你看到什么被干掉」来分，而不是按代码里的 Guard 分：
 *
 *   限制提示   —— 各类封禁文案的开关
 *   点赞       —— 点赞相关
 *   关键词     —— 吐司和文字共用的一份词表
 *   进阶       —— 需要手动填 id 的兜底手段
 *   调试 / 关于
 */
@Composable
fun SettingsScreen() {
    val settings = SettingsBridge.settings
    val connected = SettingsBridge.serviceConnected

    // 必须放在 LazyColumn 外面：item {} 各自是独立作用域
    var keywordDraft by remember(settings.toastKeywords) {
        mutableStateOf(TextFieldValue(Keywords.encode(settings.toastKeywords)))
    }
    var idDraft by remember(settings.hideViewIds) {
        mutableStateOf(TextFieldValue(ViewIds.encode(settings.hideViewIds)))
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
                        summary = if (connected) {
                            "改动即时生效，不用重启抖音"
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
                        checked = settings.blockToast,
                        onCheckedChange = { SettingsBridge.setBlockToast(it) },
                        title = "屏蔽限制类弹窗",
                        summary = "「点赞功能已封禁」这类一闪而过的提示，直接不弹",
                    )
                    SwitchPreference(
                        checked = settings.hideImBanTips,
                        onCheckedChange = { SettingsBridge.setHideImBanTips(it) },
                        title = "去掉消息页顶部横幅",
                        summary = "「消息发送功能已被禁止使用」那条横条",
                    )
                    SwitchPreference(
                        checked = settings.hideSendStatus,
                        onCheckedChange = { SettingsBridge.setHideSendStatus(it) },
                        title = "去掉聊天里的红叹号",
                        summary = "消息发送失败时，气泡左边那个红色感叹号",
                    )
                    SwitchPreference(
                        checked = settings.hideText,
                        onCheckedChange = { SettingsBridge.setHideText(it) },
                        title = "抹掉带关键词的文字",
                        summary = "含下面关键词的文案整段隐藏，比如被封禁的理由",
                    )
                }
            }

            // ---------------------------------------------------------- 点赞
            item(key = "digg") {
                SmallTitle(text = "点赞")
                Card(modifier = Modifier.cardInset()) {
                    SwitchPreference(
                        checked = settings.blockDiggUpload,
                        onCheckedChange = { SettingsBridge.setBlockDiggUpload(it) },
                        title = "点赞只在本地生效",
                        summary = "点图标、双击屏幕都能点亮，请求不发出去，服务端也就驳不回",
                    )
                }
            }

            // ---------------------------------------------------------- 关键词
            item(key = "keywords") {
                SmallTitle(text = "关键词")
                TextField(
                    value = keywordDraft,
                    onValueChange = { keywordDraft = it },
                    label = "每行一条",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
                Card(modifier = Modifier.cardInset()) {
                    ArrowPreference(
                        title = "保存关键词",
                        summary = "「屏蔽限制类弹窗」和「抹掉带关键词的文字」共用这一份",
                        onClick = {
                            SettingsBridge.setToastKeywords(Keywords.parse(keywordDraft.text))
                        },
                    )
                    ArrowPreference(
                        title = "恢复默认",
                        onClick = { SettingsBridge.resetToastKeywords() },
                    )
                }
            }

            // ---------------------------------------------------------- 进阶
            item(key = "advanced") {
                SmallTitle(text = "进阶")
                Card(modifier = Modifier.cardInset()) {
                    SwitchPreference(
                        checked = settings.hideViews,
                        onCheckedChange = { SettingsBridge.setHideViews(it) },
                        title = "按控件 id 隐藏",
                        summary = "兜底手段：用 Layout Inspect 抓到 id 后填在下面，精确点名某个控件",
                    )
                }
                TextField(
                    value = idDraft,
                    onValueChange = { idDraft = it },
                    label = "每行一个，如 0x7f0a309c",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
                Card(modifier = Modifier.cardInset()) {
                    ArrowPreference(
                        title = "保存控件 id",
                        summary = "同一个 id 可能被别的界面复用，加之前先确认清楚",
                        onClick = { SettingsBridge.setHideViewIds(ViewIds.parse(idDraft.text)) },
                    )
                    ArrowPreference(
                        title = "恢复默认",
                        onClick = { SettingsBridge.resetHideViewIds() },
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
                        summary = "会记录每次点击的控件和祖先链，平时关掉省电",
                    )
                }
            }

            // ---------------------------------------------------------- 关于
            item(key = "about") {
                SmallTitle(text = "关于")
                Card(modifier = Modifier.cardInset()) {
                    BasicComponent(
                        title = "它做了什么",
                        summary = "只改抖音客户端本地的显示和点击行为，不碰任何服务端状态，" +
                            "也不修改账号本身。",
                    )
                    BasicComponent(
                        title = "为什么有时会失效",
                        summary = "抖音更新后控件 id 和类名可能变化；打开「记录详细日志」，" +
                            "日志文件在 /storage/emulated/0/Android/data/" +
                            "com.ss.android.ugc.aweme/files/unlimit-diag.log",
                    )
                }
            }
        }
    }
}

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

private const val VERSION = "1.8.0"

/**
 * Miuix 的排版约定（来自官方 example）：
 *  - SmallTitle 与 Card 放在同一个 item 里，Card 用 Modifier.padding(horizontal = 12.dp) 内缩
 *  - Card 自身 insideMargin 为 0，内边距由内部的 BasicComponent / *Preference 提供（16dp）
 *  - TextField 是独立组件，自己带 12.dp 横向内边距，不要套进 Card
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
            item(key = "status") {
                SmallTitle(text = "状态")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = "LSPosed 框架",
                        summary = if (connected) {
                            "已连接，改动即时生效"
                        } else {
                            "未连接，请在 LSPosed 中启用本模块"
                        },
                    )
                }
            }

            item(key = "ui-elements") {
                SmallTitle(text = "界面元素")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        checked = settings.hideImBanTips,
                        onCheckedChange = { SettingsBridge.setHideImBanTips(it) },
                        title = "隐藏消息页封禁横幅",
                        summary = "去掉「消息发送功能已被禁止使用」那条提示",
                    )
                    SwitchPreference(
                        checked = settings.hideSendStatus,
                        onCheckedChange = { SettingsBridge.setHideSendStatus(it) },
                        title = "隐藏发送状态提示",
                        summary = "聊天里的红感叹号",
                    )
                    SwitchPreference(
                        checked = settings.hideText,
                        onCheckedChange = { SettingsBridge.setHideText(it) },
                        title = "按关键词隐藏文字",
                        summary = "含关键词的文字控件直接隐藏，用下面那份关键词表",
                    )
                    SwitchPreference(
                        checked = settings.hideViews,
                        onCheckedChange = { SettingsBridge.setHideViews(it) },
                        title = "按 id 隐藏控件",
                        summary = "强制把下面这些控件设为不可见，含发送失败的红感叹号",
                    )
                }
            }

            item(key = "view-ids") {
                SmallTitle(text = "控件 id")
                TextField(
                    value = idDraft,
                    onValueChange = { idDraft = it },
                    label = "每行一个，如 0x7f0ab151",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = "保存控件 id",
                        summary = "用 Layout Inspect 抓到新 id 后加在这里",
                        onClick = { SettingsBridge.setHideViewIds(ViewIds.parse(idDraft.text)) },
                    )
                    ArrowPreference(
                        title = "恢复默认",
                        onClick = { SettingsBridge.resetHideViewIds() },
                    )
                }
            }

            item(key = "digg") {
                SmallTitle(text = "点赞")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        checked = settings.blockDiggUpload,
                        onCheckedChange = { SettingsBridge.setBlockDiggUpload(it) },
                        title = "本地点赞",
                        summary = "点击图标直接变红并 +1，不经过抖音接口（滑走再回来会还原）",
                    )
                }
            }

            item(key = "toast") {
                SmallTitle(text = "提示拦截")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        checked = settings.blockToast,
                        onCheckedChange = { SettingsBridge.setBlockToast(it) },
                        title = "隐藏限制类吐司",
                        summary = "命中关键词的提示直接静默，不再弹出",
                    )
                }
            }

            item(key = "keywords") {
                SmallTitle(text = "吐司关键词")
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
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = "保存关键词",
                        summary = "命中任意一条即拦截",
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

            item(key = "about") {
                SmallTitle(text = "关于")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = "说明",
                        summary = "只修改抖音自身的本地提示，不修改任何服务端状态。" +
                            "控件 id 由抖音打包时分配，升级后可能失效，届时重新抓一次即可。",
                    )
                }
            }
        }
    }
}

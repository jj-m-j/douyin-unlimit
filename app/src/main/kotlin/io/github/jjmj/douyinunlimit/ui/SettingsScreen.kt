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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private const val VERSION = "1.0.0"

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
    var draft by remember(settings.toastKeywords) {
        mutableStateOf(TextFieldValue(Keywords.encode(settings.toastKeywords)))
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
                SmallTitle(text = "关键词")
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
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
                            SettingsBridge.setToastKeywords(Keywords.parse(draft.text))
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
                        summary = "只修改抖音自身的本地提示，不修改任何服务端状态。",
                    )
                }
            }
        }
    }
}

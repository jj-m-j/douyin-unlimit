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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val VERSION = "1.0.0"

@Composable
fun SettingsScreen() {
    val settings = SettingsBridge.settings
    val connected = SettingsBridge.serviceConnected

    // 必须提到 LazyColumn 外面：item {} 各自是独立作用域，跨 item 引用会编译不过
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
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "status-title") { SmallTitle("状态") }
            item(key = "status") {
                Card {
                    Text(
                        text = if (connected) {
                            "已连接到 Xposed 框架"
                        } else {
                            "未连接到 Xposed 框架\n请在 LSPosed 中启用本模块后重启模块 App"
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = if (connected) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }

            item(key = "toast-title") { SmallTitle("提示拦截") }
            item(key = "toast-switch") {
                Card {
                    SwitchPreference(
                        checked = settings.blockToast,
                        onCheckedChange = { SettingsBridge.setBlockToast(it) },
                        title = "隐藏限制类吐司",
                        summary = "命中关键词的吐司会被静默，不再弹出",
                    )
                }
            }

            item(key = "keywords-title") { SmallTitle("关键词") }
            item(key = "keywords") {
                Card {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.padding(vertical = 4.dp),
                        singleLine = false,
                    )
                }
            }
            item(key = "keywords-actions") {
                Card {
                    ArrowPreference(
                        title = "保存关键词",
                        summary = "每行一条，命中任意一条即拦截",
                        onClick = {
                            SettingsBridge.setToastKeywords(
                                Keywords.parse(draft.text),
                            )
                        },
                    )
                    ArrowPreference(
                        title = "恢复默认关键词",
                        onClick = { SettingsBridge.resetToastKeywords() },
                    )
                }
            }

            item(key = "about-title") { SmallTitle("关于") }
            item(key = "about") {
                Card {
                    Text(
                        text = "本模块只修改抖音自身的提示，不修改任何服务端状态。",
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

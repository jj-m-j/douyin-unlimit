# 抖音伪装

一个 LSPosed 模块：**让抖音看起来没有被限制**。

不修改服务端状态、不改账号权重，只拦截客户端本地的「限制类」提示，让它别在界面上跳出来。

## 现状

| 功能 | 状态 |
|---|---|
| 隐藏「点赞功能已封禁」这类吐司 | ✅ 已实现 |
| 可自定义拦截关键词 | ✅ 已实现 |
| 隐藏限制类界面元素（灰化/遮罩/禁用态） | ⬜ 计划中 |

## 原理

抖音的提示吐司由 **DUX Toast** 体系渲染。逆向 `抖音 40.2.0` 后确认：

```
DuxToastV2.LIZJ(context, icon, iconTint, text, ..., style, ...)
    ├─ style == DuxCustom -> new PopupToast(context, view)   // 自绘浮层
    └─ 否则               -> new DuxSystemToast(context)     // 系统 Toast + setView
```

两条显示路径都从同一个构造方法分叉，所以模块把它当收口点：拿到 `CharSequence` 参数，
命中关键词就直接返回 `null`，两种样式一起挡掉。旧版 `DuxToast` 结构相同，一并处理。

弹窗的文案不是硬编码的（部分是服务端下发），所以只能按关键词匹配，不能写死某个字符串。

另外模块还挂了 `android.widget.Toast#show` 作为兜底，覆盖不经过 DUX 的零散调用。

`DuxToastV2` / `DuxToast` 的类名是稳定的，但方法名基本被混淆（`LIZJ`、`LJ`、`LJFF` …）。
模块不写死方法名，而是遍历所有「接收 `CharSequence` 且返回 `void` 或引用类型」的方法挂 hook，
这样抖音升级改名也照样能命中。

## 环境要求

- Android 10+ (API 29+)
- LSPosed / 支持 **libxposed API 102** 的框架
- 目标应用：抖音 `com.ss.android.ugc.aweme`

## 构建

本地构建需要 JDK 21 + Android SDK (platform 36)。

```bash
./gradlew :app:assembleRelease
```

推送到 `main` 或打 `v*` tag 会自动触发 GitHub Actions 构建，产物在 Artifacts / Releases 里。

签名：默认在 CI 里现场生成 keystore（**每次构建签名都不同，覆盖安装需要先卸载**）。
要固定签名，在仓库 Secrets 里配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

## 使用

1. 安装 APK
2. 在 LSPosed 里启用模块，作用域勾选「抖音」
3. 强制停止抖音后重新打开
4. 打开模块 App 调整关键词（改完即时生效，不用重启抖音）

## 界面

设置界面用 [Miuix](https://github.com/compose-miuix-ui/miuix) 构建
（`top.yukonga.miuix.kmp:miuix-ui`，Compose Multiplatform）。

## 许可

Apache-2.0

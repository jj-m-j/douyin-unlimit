# 抖音伪装

一个 LSPosed 模块：**让抖音看起来没有被限制**。

不修改服务端状态、不改账号权重，只拦截客户端本地的「限制类」提示，让它别在界面上跳出来。

## 现状

| 功能 | 状态 |
|---|---|
| 隐藏「点赞功能已封禁」这类吐司 | ✅ 已实现 |
| 可自定义拦截关键词 | ✅ 已实现 |
| 隐藏消息页「消息发送功能已被禁止使用」横幅 | ✅ 已实现 |
| 按控件 id 隐藏界面元素（如发送失败的红感叹号） | ✅ 已实现 |
| 其它限制类界面元素 | ⬜ 持续补充中 |

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

## 消息页封禁横幅

消息 tab 顶部那条「消息发送功能已被禁止使用」走的是另一条链路：

```
ChatBanTipsLogic (extends PriorityLogic)   // 显示/隐藏判定
  LJLLLLLL()V
    banInfo   = LX/0xtl.LIZ()                   // 本地缓存的封禁信息
    punishIds = banInfo?.LJ() ?: emptyList()    // 封禁记录 id
    shownIds  = IMKevaConfig 里已展示过的 id
    if (punishIds.isEmpty()) { LJLLL(); return }                     // 隐藏
    for (id in punishIds) if (id !in shownIds) { LJLLLL(); return }  // 显示
    LJLLL()                                                          // 隐藏

ChatBanTipsUI (extends RipsUI)             // 渲染，整个类只服务这一条横幅
  a = DuxImageView ← 0x7f0a5e36  （铃铛图标）
  b = DuxTextView  ← 0x7f0ac401  （标题文字）
```

这个 Logic 类只服务于这一条横幅，所以模块直接把 `LJLLLLLL()` 变成空操作，横幅永远不会被 show。
另有「伪装无封禁记录」开关，兜底把 `ImBanInfo` 的封禁列表置空，让它自己走
`no punish id → 隐藏` 分支。

`0x7f0ac401` 这个 id 是在真机上用 Layout Inspect 抓出来核对过的。

## 按控件 id 隐藏界面元素

聊天里那些「违反社区规定」提示是**服务端下发**的（既不在 dex 字符串里，也不在资源表里），
所以没法按文案匹配。这一类只能按控件 id 处理。

模块拦的是 `View.setVisibility(int)`：命中黑名单的控件，把任何「显示」请求改写成 `GONE`。

**为什么不在 inflate 时隐藏**：聊天列表是 RecyclerView，控件会被复用，每次 rebind 都会重新
toggle 可见性。比如发送状态组件 `StatusIconWithText`：

```smali
LJI():
    if (message.getMsgStatus() >= 2) return
    textView.setText(resources.getString(0x7f11501a))
    textView.setVisibility(VISIBLE)      // ← 每次 bind 都会重新显示
```

在 inflate/adapter 层隐藏会被下一次 rebind 覆盖，只有拦 `setVisibility` 才拦得住。

代价是 `setVisibility` 属于热路径，所以做了两件事压低开销：配置摊平成 `IntArray`
（判定只做一次线性扫描，无装箱无分配），未命中时直接 `proceed`。

默认黑名单：

| id | 控件 |
|---|---|
| `0x7f0ab151` | `ImImageView` — 发送状态图标（红感叹号） |
| `0x7f0aa9d7` | `DrawChildOptEllipsizeLayout` — 状态文字容器 |

⚠️ 这些 id 是 aapt 打包时分配的，**抖音升级后可能变化**。失效时用 Layout Inspect 重新抓一次，
在模块里改掉即可（输入接受 `0x7f0ab151` 或十进制）。模块也只在每个 id 第一次命中时打一条日志，
logcat 搜 `DouyinUnlimit` 能看到实际拦到了什么。

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

# 抖音伪装模块 —— 开发技术文档

> 记录整个开发过程中的**技术决策、逆向结论、踩过的坑和验证方法**。
> 目的：让后续维护者（包括未来的自己）不必重走一遍弯路。

---

> ## ⚠️ v1.14 修订说明（先读这个）
>
> 本文档 v1.13 及之前的版本里有**四条结论是错的**，已在对应章节就地更正：
>
> | 位置 | 原来的结论 | 更正 |
> |---|---|---|
> | §6.1 | DUX 吐司两条显示分支从 `LIZJ` 一个方法分叉 | `LIZJ` 是 **static** 的「造系统吐司」函数（参数第一个是 `DuxToastV2` 自己）；自绘浮层走的是另一个方法 `LIZLLL`（`makeShowCustomToast`）。**收口点不止一个**，所以改成按「参数含 `CharSequence`/`String`」泛化扫描 |
> | §6.2 | 让 `ChatBanTipsLogic.LJLLLLLL()` 空操作即可 | `LJLLLLLL` 只是**判定**；真正把横幅挂上去的是 `LJJJLZIJ()`（内含 `im_message_block_notice_show` 埋点）。两个都要拦 |
> | §6.3 | 只拦 `StatusIconWithText.LIZ/LJI` 即可 | 图标只有**基类** `LX/179c.LIZ()` 能显示，`LJI()` 完全不碰图标；而且那个 ImageView 在 cell 布局里**可能默认就 VISIBLE**，还必须按实例把 VISIBLE 请求压成 GONE（这正是旧版按资源 id 压制在干的活，删掉就漏了） |
> | §7.5 | 驳回回滚走 `VideoDiggView.onEventDiggUpdate` | 那是**跨页面同步**用的 EventBus 广播。回滚走 `FeedDiggPresenter.LJJJJZ(Exception)`，见 §7.5 |
>
> 后两条的失败方式最有代表性：
>
> - **§7.5：hook 挂上了、开关开了、一次都没命中。** 真机日志里「已挂钩 `onEventDiggUpdate`」
>   和「用户点了赞」同时存在、却没有任何命中记录 —— 这就是「挂上但从未命中」的铁证。
> - **§6.3：命中或不命中都不重要，因为拦错了层。** 控件在布局里默认 VISIBLE 的话，
>   拦「显示方法」等于什么都没做。
>
> 一句话：**静态分析给的是假设，命中记录才是结论**；而「拦哪一层」比「拦哪个方法」更容易搞错。
>
> v1.14 同时把七个开关合并成四个，理由见 §6.0。

---

## 0. 项目概况

| 项 | 值 |
|---|---|
| 仓库 | `jj-m-j/douyin-unlimit` |
| 包名 / applicationId | `io.github.jjmj.douyinunlimit` |
| 目标应用 | 抖音 `com.ss.android.ugc.aweme` 40.2.0（versionCode 400201，targetSdk 34） |
| 模块框架 | libxposed API **102**（`io.github.libxposed:api` / `:service`） |
| UI | Miuix `top.yukonga.miuix.kmp:miuix-ui` 0.9.4（Compose Multiplatform） |
| 构建 | AGP 9.4.1 + Kotlin 2.4.20 + Gradle 9.8.0，compileSdk **37.2** |
| 当前版本 | **1.14.0**（versionCode 23） |
| 产物 | ~1.13 MB（单 dex） |

**产品目标**：让用户觉得「自己的抖音没有被限制」——隐藏各类封禁提示、让点赞看起来正常。

**设计红线**：只改客户端本地的**显示与交互**，不修改任何服务端状态，不伪造网络请求。

### 开关（v1.14：七个 -> 四个）

| 开关 | 默认 | 实际覆盖 |
|---|---|---|
| 别提示我被限制了 | 开 | 弹窗吐司 / 消息页横幅 / 聊天红叹号 |
| 连页面里写的限制说明也抹掉 | 开 | 按关键词匹配运行时文字（有误伤风险，所以单独一个开关） |
| 点赞被驳回也不回滚 | 开 | `FeedDiggPresenter.LJJJJZ(Exception)`，见 §7.5 |
| 记录详细日志 | 关 | 点击探针 + 点赞链路每一步 |

合并的理由见 §6 开头；删掉的东西见 §6.6。

| 项 | 值 |
|---|---|
| 仓库 | `jj-m-j/douyin-unlimit` |
| 包名 / applicationId | `io.github.jjmj.douyinunlimit` |
| 目标应用 | 抖音 `com.ss.android.ugc.aweme` 40.2.0（versionCode 400201，targetSdk 34） |
| 模块框架 | libxposed API **102**（`io.github.libxposed:api` / `:service`） |
| UI | Miuix `top.yukonga.miuix.kmp:miuix-ui` 0.9.4（Compose Multiplatform） |
| 构建 | AGP 9.4.1 + Kotlin 2.4.20 + Gradle 9.8.0，compileSdk **37.2** |
| 产物 | ~1.13 MB（单 dex） |

**产品目标**：让用户觉得「自己的抖音没有被限制」——隐藏各类封禁提示、让点赞看起来正常。

**设计红线**：只改客户端本地的**显示与交互**，不修改任何服务端状态，不伪造网络请求。

---

## 1. 环境与构建：第一个大坑

### 1.1 沙箱的根本限制

开发环境是 **aarch64 proot（Ubuntu 24.04）**。这带来一个硬约束：

> **本地无法编译任何 Android 资源。**

原因链：

1. Google 官方 `aapt2` 只发布 **x86-64** 版本（Maven 上的 `com.android.tools.build:aapt2` 只有 `-linux` / `-osx` / `-windows` 分类器），apktool 自带的 `prebuilt/linux/aapt2_64` 同样是 x86-64。
2. 想用 `qemu-user-static` 转译 x86-64 → 装上后报：
   ```
   qemu-x86_64-static: aapt2_64: Unable to find a guest_base to satisfy all guest address mapping requirements
   ```
   proot 环境下无法提供 guest_base 所需的地址空间映射，**此路不通**。
3. 结论：任何需要 aapt2 的步骤（编译资源、link 资源表、生成二进制 AXML）本地都做不了。

**仍然能在本地做的**（用于小改造类项目）：
- `javac` + `d8`（`work/tools/r8.jar`）产出 dex
- `apktool d` 解包（纯 Java 解码，不需要 aapt2）
- 解包后核对 `AndroidManifest.xml` 的合并结果、验证 `META-INF/xposed/*` 等

### 1.2 GitHub Actions 构建（唯一可行路径）

`ubuntu-latest` runner 是 x86-64，aapt2 原生可用，标准 AGP + Gradle + Compose 全套都能跑。

CI 里**逐个踩过的坑**（都已修好，写在这里省一次调试）：

| # | 现象 | 原因 | 修法 |
|---|---|---|---|
| 1 | `Warning: Failed to find package 'tools'` | `android-actions/setup-android@v3` 内部执行 `sdkmanager tools`，而 legacy `tools` 包已从 Google 仓库下架 | **删掉这个 action**，runner 自带 SDK |
| 2 | `sdkmanager: command not found` | runner 上 `cmdline-tools/bin` 不在 PATH | `find "$SDK/cmdline-tools" -name sdkmanager \| sort -V \| tail -n1` 自己定位 |
| 3 | `20 issues were found when checking AAR metadata`：`requires ... compile against version 37 or later` | Miuix 0.9.4 的 AAR metadata 里 `minCompileSdk=37`（**AAR manifest 里看不出来**，只在 `META-INF/com/android/build/gradle/aar-metadata.properties`） | compileSdk 提到 37.2 |
| 4 | `platforms;android-37` 装不上 | Android 16 之后引入 **minor API level**，平台名是 `platforms;android-37.2` / `-37.1` / `-37.0`，没有裸的 `android-37` | 装 `platforms;android-37.2`，DSL 写 `release(37) { minorApiLevel = 2 }` |
| 5 | `Type argument for reified type parameter 'T' was inferred to the intersection of ['Comparable<String & Boolean>' & 'Serializable']` | Kotlin 对 `arrayOf(aid, true)` 里 `String` + `Boolean` 推断出奇怪的交集类型 | 写成 `arrayOf<Any?>(aid, true)` |
| 6 | 推送到 GitHub 报 `could not read Password for 'https://ghp_xxx@github.com'` | `https://TOKEN@host` 这种写法里 token 被当作**用户名**，git 会去弹窗要密码；无 tty 就报这个 | 写成 `https://x-access-token:TOKEN@github.com/...` |
| 7 | `Bad credentials` | **令牌字符串打错了一个字符** | 用文件写入避免手抖，并先 `GET /api/v2/user` 验证 |
| 8 | 产物下载 `transfer closed with N bytes remaining` | 网络抖动 | `unzip -t` 校验完整性 + 重试循环 |

**CI 自身的可用技巧**：
- 抓构建日志：`GET /repos/{o}/{r}/actions/runs/{id}/logs`（返回 zip）
- 抓产物：`GET /repos/{o}/{r}/actions/artifacts/{artifactId}/zip`
- 在 Verify 步骤里 `unzip -l` + `grep` 校验 `META-INF/xposed/` 是否存在、`apksigner verify --print-certs` 校验签名——**产物没校验过的构建不算过**

**AGP 9 的 DSL 变化**（照抄一个真实跑通的模块能省很多事）：
```kotlin
compileSdk { version = release(37) { minorApiLevel = 2 } }   // 不再是 compileSdk = 37
buildTypes.release {
    optimization.enable = true        // 不再是 isMinifyEnabled
    vcsInfo.include = false
}
dependenciesInfo { includeInApk = false; includeInBundle = false }
packaging { dex { useLegacyPackaging = true } }
```
另外 **AGP 9 自带 Kotlin 支持**，Android 模块里不需要再 apply `org.jetbrains.kotlin.android`。

---

## 2. 体积优化：一个「想当然」的错误

**最初我在 `build.gradle.kts` 里写了 `optimization.enable = false`**，理由写在注释里：

> 模块大量依赖反射 hook，关掉 R8 以免类名/方法被裁剪

**这个理由是错的。** 我们反射的是**抖音的类**（`DuxToastV2`、`ChatBanTipsLogic`…），R8 只处理自己 APK 里的代码，够不着别人的 APK。自己代码里唯一必须保命的是入口类 `HookEntry`（`META-INF/xposed/java_init.list` 按全名加载），一条 keep 规则就够。

代价：**24.26 MB**，其中 `classes3.dex` 11 MB 全是没被引用到的 Compose/Miuix/Kotlin 标准库。

修完之后的数字：

| | 之前 | 之后 |
|---|---|---|
| APK | 24.26 MB | **1.13 MB** |
| dex | 3 个（12.8M + 0.5K + 11.2M） | 单个 classes.dex，2.19 MB 未压缩 |

三个手段，按收益排序：**开 R8** > dex 改压缩打包（`useLegacyPackaging`，默认不压缩是为了 mmap，小应用不值当） > 排除 `META-INF/*` 冗余与 `kotlin/**`。

---

## 3. LSPosed / libxposed API 102 接入

### 3.1 依赖与元数据

```kotlin
compileOnly(libs.libxposed.api)      // 运行时由框架提供
implementation(libs.libxposed.service) // 必须打进 APK
```

`service` 必须 `implementation`，因为 **`XposedProvider` 是在 service AAR 自己的清单里声明的**：

```xml
<provider android:name="io.github.libxposed.service.XposedProvider"
          android:authorities="${applicationId}.XposedService"
          android:exported="true" />
```

依赖一加，这个 provider 就自动合并进我们的 APK——**不用手写**。authority 是 `${applicationId}.XposedService`。

模块元数据放在 `app/src/main/resources/META-INF/xposed/`（AGP 会把 `src/main/resources` 打进 APK 根目录）：

| 文件 | 内容 |
|---|---|
| `module.prop` | `minApiVersion=102` / `targetApiVersion=102` / `staticScope=true` / `exceptionMode=protective` |
| `scope.list` | `com.ss.android.ugc.aweme` |
| `java_init.list` | `io.github.jjmj.douyinunlimit.xposed.HookEntry` |

### 3.2 配置同步

- 被注入进程（抖音）读配置：`XposedModule.getRemotePreferences("settings")`
- 模块 App 写配置：`XposedServiceHelper.registerListener` 拿到 `XposedService` → `getRemotePreferences("settings")`
- **改配置不需要重启抖音**：hook 在进程启动时全部装好，开关只影响拦截时的行为

### 3.3 三个必须知道的坑

**① `onPackageReady` 在同一进程会被调用两次**

真机日志里能看到安装块成对出现。后果很严重：

- 所有 hook 装两遍 → 开销翻倍（功耗）
- **有状态的逻辑被破坏**：双击检测的两个拦截器共用同一个 `lastVideoTapAt`，第一个写完后第二个立刻看到间隔 `0ms` → **每一次单击都被判成双击**

修法：用一个 `AtomicBoolean` 保证只装一次。

**② `Application.onCreate` 会被触发多次**

日志里能看到十几段 `===== 抖音进程 Application.onCreate =====`。原因是抖音用了 Hive 插件框架，会创建多个 Application 实例。任何「只做一次」的初始化都不能简单地挂在 `onCreate` 上。

**③ `XposedModule.log()` 既不进 logcat 也不进文件**

它走框架自己的日志通道。**所有诊断必须走自建的日志通道**，否则会出现「hook 明明挂上了但日志里什么都没有」的假象，白白浪费好几轮。

---

## 4. 日志系统：花的代价最大的一块

日志本身经历五代演进，前四代都是失败的：

| 代 | 做法 | 失败原因 |
|---|---|---|
| 1 | 写 logcat，让用户 `logcat -s DouyinUnlimit` | 环形缓冲太小早滚掉；`tail -20` 把关键行截掉；过滤参数易踩坑。用户反馈「啥也没输出」 |
| 2 | 改写成文件 | `resolveFile()` 在**失败时也缓存了「已解析」**；而 `onPackageReady` 时 Application 还没创建，`currentApplication()` 返回 null → 从此永远拿不到文件 |
| 3 | 改到 `Application.onCreate` 初始化 + 挂 `Application` 钩子作为「注入成功」的铁证 | 仍是空的 |
| 4 | — | **文件被覆盖**：`startSession` 用 `writeText` 写文件头＝**清空重写**；多进程 + 多次 `Application.onCreate` 互相抹掉，最后只剩两行 |
| 5 | **只追加不截断** + 每行标进程名 `[main]`/`[miniappX]` + 内存缓冲 | ✅ 终于稳定 |

**第 5 代的三个设计点**：

1. **永不截断，只追加**，每次会话写一条带进程名的分隔行——谁也盖不掉谁
2. **内存缓冲**：文件不可用时的日志先存内存（早期是 `onPackageReady` 阶段的安装日志），等 Application 就绪后一次性落盘，**启动阶段的完整顺序都能保留**
3. **两级日志**：
   - `Diag.log()` 基础日志，始终写（有总量上限）
   - `Diag.debug()` 详细日志，只在用户打开「详细调试日志」开关时写——**平时不白耗电**

日志文件位置（MT 用 root 可直接打开）：
```
/storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
```

### 4.1 点击探针：终结「猜控件」的那一步

上面所有手段解决的是「日志出不来」。但还有一类浪费是**猜错控件**——反复判断错类名、错 id，每一轮都是一次完整的「改代码 → CI → 装包 → 试 → 贴日志」。

于是加了一个 **ClickProbe**：hook `View.performClick()`，把被点控件的**类名 + id + 完整祖先链**记下来。

打开开关点一下目标控件，日志里就直接写出它的身份：

```
[click*] 点击 android.widget.FrameLayout #0x7f0a309c
         ← LinearLayout(0x7f0a30b1) < HPFrameLayout(0x7f0a30a4)
         < FeedRightScaleView(0x7f0a9ef5) < ...         ← 右侧操作栏
```

**这一条日志直接终止了整个「猜控件」阶段**。点赞按钮的 id `0x7f0a309c` 就是这么确定的，不是猜的。

> 方法论：当你在反复猜某个东西的身份时，**先造一个能把它打印出来的工具**，而不是继续猜。

---

## 5. 逆向方法论

从这份工作里沉淀下来的几条：

### 5.1 先确认文案的来源层级

看到一条界面文案，按这个顺序排：

1. **dex 字符串**（`dex_strings` / `dex_string_members`）
2. **资源表字符串**（`resource_table_values`）
3. 都没有 → **服务端下发**

三种来源对应完全不同的拦截方式：

| 来源 | 拦截方式 |
|---|---|
| dex 硬编码 | 找到那个类的方法，按类名 hook |
| 资源表 | 反查谁调用了这个资源 id |
| 服务端下发 | **只能按运行时文字内容匹配**（见 §6.4） |

例：`点赞功能已封禁` 既不在 dex 也不在资源表 → 服务端下发。而 `消息发送功能已被禁止使用` 在 dex 里（硬编码在 `LX/0xvb;` 的构造函数里）。

### 5.2 按「消费方」倒推，而不是只看字符串

`dex_string_members` 能直接告诉你**哪个方法用到了这个字符串**：

```
查 "消息发送功能已被禁止使用"
  → dex_method:LX/0xvb;-><init>()V            （写死的地方）
  → xref LIZ()Ljava/lang/String;
  → dex_method:...InputDisableServiceLogic;->LJJJJ(Z)Ljava/lang/CharSequence;   （消费方）
```

### 5.3 按「结构特征」找方法，不按名字

抖音的类名通常稳定（`DuxToastV2`、`ChatBanTipsLogic`、`VideoDiggView`），
但**方法名大量被混淆**（`LIZJ` / `LJ` / `LJFF`）。所以：

- 吐司：遍历所有「收 `CharSequence` / `String` 且返回 `void` 或引用类型」的方法挂 hook
- 消息页横幅 / 聊天发送状态：先按记下来的方法名找，找不到就退化成
  「该类所有 public 无参 void 方法」——这两个类各自只服务一个组件，全屏蔽也没副作用
- 点赞回滚：按「名字 + 单个 `Exception` 参数」定位（回滚方法必然收一个异常）
- 点赞诊断钩子：`LJJJJL`（1 参，点击入口）/ `LJJLIIIJJI`（3 参，发请求）

这样即使抖音改名也照样工作。

> **这个方法有个隐藏前提：你对链路已经有正确的假设。**
> v1.13 用「参数是 `Function2`」找到的点赞入口是对的，可把回滚定位在
> `onEventDiggUpdate` 就错了——结构特征只能帮你在候选里挑，挑错了照样一次都不命中。
> 所以**每个结构特征 hook 都必须带命中日志**（见 §7.6 的三分法）。

### 5.4 真机证据 > 静态推测

两条互补的证据来源：

1. **Layout Inspect**（用户手动抓）：给出控件的类名和 id
2. **自己写的点击探针**（§4.1）：给出「被点击的那个控件是谁，以及它的祖先链」

两者交叉验证。**注意**：Layout Inspect 抓到的东西可能不是你想要的——用户早期给的 `DiggAnimationView` 其实是点赞**动画**的载体，不是按钮；`0x7f0aa9d7` 那次其实是会话列表的标题，不是聊天的提示。

### 5.5 一个资源 id 可能被多个界面复用

**踩过的坑**：把 `0x7f0aa9d7`（`DrawChildOptEllipsizeLayout`）加进隐藏列表，结果**消息页的会话名全部消失**。原因是 `RipsSessionListAdapter` 也用它：

```smali
new-instance v12, Lcom/ss/android/ugc/aweme/im/utils/checkdraw/DrawChildOptEllipsizeLayout;
const v0, 0x7f0aa9d7
invoke-virtual {v12, v0}, Landroid/view/View;->setId(I)V
```

**结论：往 id 黑名单里加东西之前，必须先确认它在目标之外没有第二个使用点。** 正因为这个风险，类名 hook 永远优先于 id hook。

---

## 6. 已实现功能的技术细节

### 6.0 为什么重组了开关

v1.13 有七个开关，其中四个其实在描述**同一件事**——「抖音在告诉我我被限制了」，
只是实现分别落在四层：

| 现象 | 实现层 | 误伤风险 |
|---|---|---|
| 弹出来的吐司 | DUX Toast 体系 + 系统 Toast | 无（按类名 + 关键词） |
| 消息页顶部横幅 | `ChatBanTipsLogic` | 无（整个类只服务这条横幅） |
| 聊天里的红叹号 | `StatusIconWithText` | 无（整个类只服务这个组件） |
| 散落的限制文案 | `TextView.setText` 文字匹配 | **有**（见下） |

用户想消掉的是**现象**，不是实现。前三者按固定类精准拦截、零误伤，
合成一个开关「别提示我被限制了」；第四个靠运行时文字匹配、可能误伤正常内容，
单独保留，让用户能只关掉它。

拆成四个开关的代价是：用户被迫去做一个他并不关心的技术区分。
合并之后行为完全一样（四个旧开关默认都是开的），但界面少了一半杂音。

---

### 6.1 弹窗吐司

DUX Toast 体系（`com.bytedance.dux.toast`）。**它不是单一收口点** ——
v1.13 的文档说「两条显示分支从 `LIZJ` 一个方法分叉」，这是错的。
真实情况是：

```
系统吐司   DuxToastV2.LIZJ(DuxToastV2, Context, Drawable, CharSequence, ...,
                          DuxToastLocation, ...)          <- static；第 3 位才是文案
便捷入口   DuxToastV2.LJFF(Context, CharSequence)
           DuxToastV2.makeShowSystemToast$default(..., CharSequence, ...)
自绘浮层   DuxToastV2.LIZLLL(... DuxToastContent ...)     <- makeShowCustomToast，拿不到文案
           DuxToastV2.LJ(Context, boolean, String, Function1)     <- 这里是 String
           DuxToastV2.customToastShow$default(Context, String, ...)
```

**做法**：不写死方法名。遍历 `DuxToastV2` / `DuxToast` /
`DuxToastContent$DuxToastTextContent` 里「参数含 `CharSequence` 或 `String`」且
「返回 `void` 或引用类型」的方法，命中关键词就跳过。抖音改方法名也照样工作。

（`makeShowCustomToast$default` 的文案包在 `DuxToastContent` 抽象类里，
直接拿不到——这一路靠 §6.4 的文字匹配兜住。）

**为什么必须在吐司被创建之前拦掉**：自绘吐司是一个 `PopupToast` 浮层。
把里面的 TextView 藏掉只会剩下一个**空药丸壳**，看起来更奇怪。
所以「按关键词隐藏文字」（§6.4）**不能**取代这一层。

**返回值的坑**：部分吐司方法返回 `IDuxToastOperation`，调用方之后会拿它 `dismiss()`，
直接返回 `null` 会让调用方 NPE。所以返回类型是**接口**时，回一个
「什么都不做」的动态代理（`java.lang.reflect.Proxy`，按返回类型缓存，别每次新建）。

**兜底**：`android.widget.Toast#show()`，覆盖不经过 DUX 的零散调用。

### 6.2 消息页封禁横幅

**两个入口都要拦**，只拦判定会被绕过：

| 方法 | 作用 |
|---|---|
| `ChatBanTipsLogic.LJLLLLLL()V` | 显示/隐藏**判定** |
| `ChatBanTipsLogic.LJJJLZIJ()V` | **真正把横幅挂上去**（内含 `im_message_block_notice_show` 埋点、`TOP_BAR`） |

判定逻辑（`LJLLLLLL`）：

```
banInfo   = LX/0xtl.LIZ()                    // 从 Keva "imBanInfoSp" 读本地缓存
punishIds = banInfo?.LJ() ?: emptyList()
shownIds  = IMKevaConfig 里已展示过的 id
if (punishIds.isEmpty()) { LJLLL(); return }                     // 隐藏
for (id in punishIds) if (id !in shownIds) { LJLLLL(); return }  // 显示
LJLLL()                                                          // 隐藏
```

`ChatBanTipsUI`（渲染）：`a` = DuxImageView（0x7f0a5e36，铃铛），
`b` = DuxTextView（0x7f0ac401，文字）—— 真机 Layout Inspect 核对过。

### 6.3 聊天里的红叹号 / 发送状态

```
LX/179c （基类，发送状态指示）
  b: ImageView                                     // 状态图标（红叹号）
  LIZ()V     -> b.setImageResource + b.setContentDescription
                + b.setVisibility(VISIBLE)         ★ 图标唯一的显示点
  LIZLLL()V  -> b.setVisibility(GONE)              // 隐藏
  LIZIZ/LIZJ -> throw NPE（留给子类实现的占位）

StatusIconWithText extends LX/179c    f: DmtTextView（说明文字）
  LIZ()V  -> invoke-super LIZ()（显示图标）+ 设置并显示 f      ★ 显示
  LJI()V  -> 只动 f，**完全不碰图标**
  LIZJ()V -> 把图标和文字都藏起来，再调 LJI()
  LIZLLL()V -> super.LIZLLL() + f.setVisibility(GONE)
```

**关键：图标只有基类 `LX/179c.LIZ()` 能显示，`LJI()` 跟它毫无关系。**
所以「只拦子类的 `LIZ` / `LJI`」是**不够的**（v1.14 初版就是这么写的，红叹号漏了），
要做三重保险：

| # | 拦什么 | 挡住什么 |
|---|---|---|
| 1 | 子类 `LIZ` / `LJI` | 它的 `invoke-super` 和说明文字 |
| 2 | 基类 `LIZ()` | 任何直接走基类显示图标的路 |
| 3 | **构造时把 `b` 登记进压制表** | **图标在 cell 布局里默认就 VISIBLE 的情况** |

第 3 条是关键、也是最反直觉的一条：

> **如果控件在 XML 里默认就是 VISIBLE，拦「显示方法」等于什么都没做** ——
> 它压根不需要被「显示」就已经亮着了。这类控件只能靠
> 「把任何 VISIBLE 请求改写成 GONE」压住。

登记用的是**实例**而不是资源 id。旧版按 id 拉黑名单踩过两次坑：
`0x7f0aa9d7` 同时也是会话列表的标题，加进黑名单后消息页的会话名全没了；
而且资源 id 是 aapt 打包时分配的，抖音升级就会变。按实例登记既精确又不受版本影响。

登记动作挂在「显示方法」和「构造方法」两处：前者一定能拿到实例，
后者覆盖「布局默认 VISIBLE、显示方法从没被调用过」的情况。两条都是低频路径。

不动 `LIZJ` / `LIZLLL` 这些隐藏路径，避免和它自己的状态机打架。

> **固有副作用**：消息真的因为网络原因发送失败时，用户也看不到红叹号了，
> 也就不知道要重发。这是这个功能的代价，不是 bug。

### 6.4 按关键词隐藏文字（通用兜底层）

**为什么需要它**：服务端下发的封禁文案既不在 dex 也不在资源表，而且同一类提示
散落在多处（会话里的系统消息、聊天里的提示行、列表里的横幅……），按控件 id 点名是打地鼠。

**做法（两层，缺一不可）**：

```
TextView.setText(CharSequence)
    └─ 命中关键词 -> 登记进 blocked(WeakHashMap) + 立即 GONE

View.setVisibility(int)
    └─ 控件在 blocked 里 -> 任何 VISIBLE 请求都改写成 GONE
```

**为什么必须两层**：调用方经常在 `setText` **之后**再补一次 `setVisibility(VISIBLE)`
把它显示回来：

```smali
LJI():
    textView.setText(...)            // 这里识别到并标记
    textView.setVisibility(VISIBLE)  // 紧接着又被显示回来 <- 必须靠第二层压住
```

**必须跳过 `EditText`** —— 否则用户自己在输入框打「封禁」两个字，输入框会当场消失。

### 6.5 点赞不回滚

完整分析见 §7.5。一句话：拦 `FeedDiggPresenter.LJJJJZ(Exception)`，
它是「失败后撤销乐观点赞」在整个链路里唯一的出口。

### 6.6 曾经存在、已删除

| 功能 | 为什么删 |
|---|---|
| 按控件 id 隐藏 | `ViewIds.DEFAULT` 本来就是**空列表**——默认状态下它什么都不做，只是一个「得先会用 Layout Inspect 才用得起来」的占位开关。相关风险见 §5.5。**注意它背后的能力后来以更好的形式回来了**：§6.3 需要「把某个控件永远压成 GONE」，但改成了**按实例登记**——只压住自己抓到的那个 View 对象，不会像资源 id 黑名单那样误伤复用同一 id 的其它界面 |
| 拦截点赞 HTTP 请求（`NetGuard`） | 方案已证伪（§7.1）：点赞走 TTNet/Cronet 原生栈，Java 侧拦不到。留着是 185 行死代码 + 每个进程两个永不命中的 hook |

## 7. 点赞：最长的一条路（完整弯路记录）

这条功能反复失败了很多轮，记录全过程因为它最能说明问题。

### 7.1 第 1 步：想拦「上传服务器」

动机很正当：请求不发出去，服务端就无从驳回，也就不存在回滚。

| 尝试 | 结果 |
|---|---|
| 找出点赞 API 类 `LX/1JCu.diggItem(...)` | 它是 **Retrofit 接口**（返回 `ListenableFuture`），没有具体实现类可挂 |
| 找出包装层 `LX/1JCt`，把回调改写成 success | **无效**（回滚不由这个回调驱动） |
| 挂 `okhttp3.OkHttpClient.newCall` + `com.bytedance.retrofit2.SsHttpCall.enqueue` | 钩子**成功挂上**，但真机实测**零请求经过它们** |

**决定性结论**：

> 抖音的 API 请求走 **TTNet（Cronet 原生栈）**，请求在 native 层构建，Java 侧既拦不到也没有 URL。
> `com.ttnet.org.chromium.net.impl.CronetUrlRequest` 这一整套确实在包里。

**这条路彻底封死。** 能得出这个结论是因为我加了「抽样打印前 30 条非点赞请求 URL」——抖音打开 feed 必然发请求，只要有一个走 OkHttp 就必然留痕。一条都没有，就是证据。

### 7.2 第 2 步：想拦点击（连错四轮）

| 尝试 | 结果 | 原因 |
|---|---|---|
| `DiggAnimationView.onTouchEvent` | 挂上了但**从未被调用** | 那是点赞**动画**的载体，不是能点的按钮 |
| `View.performClick` + 向上找 `VideoDiggView` 祖先 | **从未命中** | `VideoDiggView extends AsyncBaseVideoItemView`，**它本身不是 View** 而是包着 View 的控制器，不可能出现在祖先链里 |
| 挂 `VideoDiggView` 的点击监听器（228 个 `onClick*`） | 没命中，且**开销很大** | 按钮的监听器不在那个 lambda 类里 |
| **按 id `0x7f0a309c` 精确匹配 `View.performClick`** | ✅ **成功** | id 是靠点击探针抓出来的 |

### 7.3 第 3 步：动画为什么不对

```smali
LJIIJJI(DiggAnimationView view, boolean onlyScale)V
    LJIIIZ(view, onlyScale, null)          // diggUIConfig = null

LJIIIZ(View view, boolean onlyScale, LX/1J8B diggUIConfig)V
    if (onlyScale != 0) { LX/0E36->LIZ(view); return }      // 只缩放
    if (!this.isSelected()) { LJIIL(diggUIConfig); return } // 心形特效
    LX/0E36->LIZ(view)                                      // 退化成缩放
```

**我犯了两个叠在一起的错**：

1. 传的是 `onlyScale = true` → 第一行就跳进「只缩放」
2. 又是**先设 `isSelected = true` 才调动画** → 就算传 `false`，也已选中 → 又退回缩放

结果：`invoke` 成功、无异常，但出来的是最次的效果。**修法：传 `false`，并且在设置选中态之前调用。**

### 7.4 第 4 步：双击（三次方向性修正）

**修正 1 —— 修掉双重挂载后暴露的新问题**

双重挂载掩盖了时间戳被写两次的问题，修掉之后只剩一个拦截器，而它用「两次抬手」的间隔，窗口 350ms。真实双击间隔很接近上限（实测成功那次是 **323ms**），稍慢就漏判。

**修正 2 —— 改用「按下间隔」后仍失败**

```
01:41:04.391 视频区域 ACTION_DOWN，距上次 xxxms，疑似双击=false
01:41:04.530 视频区域 ACTION_DOWN，距上次 139ms，疑似双击=true     ← 认出来了
（然后没有任何 ACTION_UP）
```

**父容器在第二次按下之后劫走了整个触摸序列**，我们的 `onTouchEvent` 只会收到 `CANCEL`。
→ 「等抬手再动作」的写法**从根上不成立**。

**修正 3 —— 在第二次按下时动作，但发现这是死路**

改成在第二次 `ACTION_DOWN` 的当下就本地化并吞掉事件。结果被识别了，但用户反馈：

> 双击经常判定为暂停视频，而且没有抖音原生的双击点赞的特效

**这两件事是因果绑定的，不是 bug**：

| 我做的 | 必然的后果 |
|---|---|
| 吞掉双击的第二次按下 | 抖音只看得到一次点击 → **判成单击 → 暂停视频** |
| 抖音收不到完整双击 | **它的原生双击特效不会播** |

**只要在触摸层拦，就必然丢掉原生手感与特效。** 换任何拦截写法都躲不掉。

### 7.5 第五步（最终方案）：不拦点击，只拦「失败后的那一次回滚」✅

让抖音完整走完它自己的一套（乐观 +1、原生动画、原生双击特效、发请求都在），
只在最后那一步按住它。拦截点用 dex 静态分析定位、并逐个 xref 验证过：

```
用户点赞
  → FeedDiggPresenter.LJJJJL(aweme)        handle_digg_click（点击入口）
  → LJJLIIJ(aweme, true, ...)              乐观设为已赞
        ↑ 这是**唯一**写 Aweme.diggSelected 的地方
  → aweme.diggSelected = 1
  → LJJLIIIJJI(aweme, "click_like")        发请求
  → 服务端驳回
  → wq(Exception)                          失败回调（feed_digg_error_monitor 埋点）
  → LJJJJZ(Exception)                      ★ 回滚：取反写回 diggSelected、弹失败提示、
                                             通知 LX/1J7T 监听者、post LX/1A7e 到 LiveData
  → 图标与数字被刷回未点赞
```

**`LJJJJZ` 只有两个调用者**（用 `mt_apk_dex_xref` 逐个确认）：

| 调用者 | 说明 |
|---|---|
| `FeedDiggPresenter.wq(Exception)` | 请求失败回调 |
| `LX/19zz.run()` | `wq` 那侧构造的转发 Runnable，内容就是调 `LJJJJZ` |

也就是说「失败后撤销乐观更新」这件事**只有这一个出口**。跳过它，UI 就停在已赞状态。

**为什么这个方案同时解决「暂停」和「特效」**：点击完全没有被碰，抖音看到的还是
完整的双击，它的手势判定和特效都照常。

**为什么顺带干掉了失败提示**：`LJJJJZ` 里除了回滚还会弹提示
（`LX/1J8U.LJFF(resId, ctx, e)`）。整个跳过 = 用户什么都不知道。

**已知限制**：服务端确实驳回了这次点赞，`Aweme.userDigg` 仍是未赞
（我们改的是本地的 `diggSelected`，而且这里连它都不撤销）。
所以下拉刷新、换一批视频之后，图标会按服务端数据恢复。
这个方案保证的是「**点赞当下不回滚**」，不是「点赞成功了」。

### 7.6 为什么 v1.13 的定位是错的（「挂上但从未命中」）

v1.13 拦的是 `VideoDiggView.onEventDiggUpdate(LX/0tvw;)V`。它是一条**真实存在**的
EventBus 订阅方法（带 `@Subscribe` 注解），命中后也确实会调
`LJJIIJZLJL(Aweme, Z, Z)` 重刷视图。所以静态上看「像个收口点」。

但它**只在跨页面同步点赞状态时才广播**（例如从聊天里点赞，通知 feed 里的视图更新）。
驳回回滚走的是 `wq` → `LJJJJZ` 那条路，**跟这个事件毫无关系**。

真机日志把这件事钉死了：

```
01:54:22.058 [digg] 已挂钩 onEventDiggUpdate(...)——驳回后不回滚   <- 挂上了
01:54:27.413 [click*] 点击 android.widget.FrameLayout #0x7f0a309c    <- 用户点了赞
(然后什么都没有)                                                      <- 一次都没命中
```

**把三种失败区分开，比任何单次修复都重要：**

| 日志表现 | 含义 | 下一步 |
|---|---|---|
| 没有「已挂钩」这一行 | **没挂上**（类名/方法名变了） | 回 dex 里重新找类名 |
| 有「已挂钩」、操作后无命中 | **挂上但从未命中** -> 拦截点选错了 | 顺着调用链找真正的出口 |
| 有命中、但现象没变 | **命中但无效** -> 拦早了/拦晚了，或拦的不是那条路 | 换链路里另一个点 |

v1.13 卡在第二类，而当时的代码只在「命中」时才打日志、挂载成功也打日志，
唯独没有区分「挂了但没命中」——所以只能靠反复试。v1.14 给点赞链路加了
三段诊断钩子（点击入口 / 发请求 / 回滚），下一轮日志就能直接指向答案。

## 8. 踩坑速查表

| 坑 | 教训 |
|---|---|
| `optimization.enable = false` 白送 20MB | 反射**别人的**类不受 R8 影响；怕裁剪只需 keep 自己的入口类 |
| 资源 id 被多界面复用（`0x7f0aa9d7`） | 加 id 黑名单前必须确认无第二使用点；类名 hook 优先 |
| 点赞数格式被改写（`371283` → `37.1万`） | **改数字不改格式**；抖音显示纯数字时就得保持纯数字。这个 bug 是用户日志发现的 |
| `onPackageReady` 双触发 | 有状态逻辑必须幂等；用 `AtomicBoolean` 保证只挂一次 |
| `Application.onCreate` 多次触发 | 抖音用 Hive 插件框架；「只做一次」的初始化不能寄望于 onCreate |
| `XposedModule.log()` 不进任何日志 | 诊断必须走自建通道，否则「hook 挂上了但日志空白」会浪费好几轮 |
| 日志文件只剩两行 | `writeText` = 清空重写；多进程会互相抹掉。改成只追加 |
| `resolveFile` 缓存了失败结果 | 早期解析失败（Application 未创建）不能缓存，要留待重试 |
| `arrayOf(String, Boolean)` 编译失败 | 显式写 `arrayOf<Any?>(...)` |
| 用 `-s TAG` / `tail -N` 过滤日志 | 前者易踩坑、后者会把关键行截掉。直接看完整文件 |
| GitHub 推送 `could not read Password` | URL 要写 `x-access-token:TOKEN@`，不能只写 token |
| 双击特效与暂停 | **在触摸层拦截 = 必然失去原生手感**，这是物理约束不是 bug |
| 猜测控件身份 | 先造一个能把身份**打印出来**的工具（点击探针），而不是继续猜 |
| 一个 `}` 多打 / 变量名笔误 / 函数改名漏改 | 改完代码先做括号平衡与符号引用的一致性检查，再推 CI |

---
| DUX 吐司「唯一收口点」 | 写死一个方法名去拦，会被换实现绕过，改成「参数含 CharSequence/String」泛化扫描 |
| 拦下的方法返回 null | 返回 `IDuxToastOperation` 的调用方之后会 `dismiss()` -> NPE。返回类型是接口时回一个空实现的动态代理 |
| 横幅只拦「判定」方法 | 真正挂横幅的是另一个方法（`LJJJLZIJ`），只拦判定会被绕过 |
| 拦截点选错（`onEventDiggUpdate`） | 静态分析给假设，**命中记录**才是结论。每个 hook 都要有命中日志，才能区分「没挂上 / 没命中 / 命中无效」 |
| 开关按「实现层」拆 | 用户想消掉的是**现象**不是实现。四个开关描述同一件事 = 让用户做无意义的选择 |
| 默认什么都不做的开关 | 「按控件 id 隐藏」默认列表是空的 = 纯占位开关，直接删 |
| 「保存」按钮 + 手动词表 | 其它开关都是即时生效，只有词表要手动存，交互不一致；改成停顿 600ms 自动保存 |

## 9. 已知限制与待办

### 限制

1. **点赞只在「当次」不回滚**：服务端确实驳回了，`Aweme.userDigg` 仍是未赞，
   下拉刷新 / 换一批之后图标会按服务端数据恢复。要跨刷新保持，得记住 aid 并在
   列表绑定后重新套用 `diggSelected`，需要先定位「视图 ↔ aid」的映射，尚未实现。
2. **无法拦截服务端请求**（TTNet/Cronet 原生栈），所以「让服务端真的接受点赞」做不到，
   只能改本地呈现。
3. **类名 / 方法名随版本变化**：v1.14 已经尽量不依赖资源 id（aapt 打包时分配，
   升级就会变，而且可能被别的界面复用），但仍然依赖类名和方法名。
   好在现在每条 hook 都会把挂载结果写进日志，便于定位。
4. **聊天红叹号有固有副作用**：真·发送失败时用户也看不到提示，不知道要重发。
5. 签名每次 CI 现场生成 -> 覆盖安装需先卸载。固定签名需配置 `KEYSTORE_BASE64` 等 secret。

### 待办

1. **真机验证 v1.14 的点赞回滚拦截**：日志里应出现 `[digg] 拦下驳回回滚`。
   如果没有，按 §7.6 的三分法先确认是「没挂上」还是「没命中」。
2. 模块改名（候选：「无事发生」/「本账号一切正常」/「查无此封」/「风控未命中」/「幻觉」）
3. 点赞跨刷新保持（见限制 1）
4. 评论区的点赞如果也被驳回，`comment.ui.diggbury` 那条链可能要同样处理

### 设置项迁移（v1.13 -> v1.14）

| v1.13 | v1.14 |
|---|---|
| `block_toast` + `hide_im_ban_tips` + `hide_send_status` | `hide_tips` |
| `toast_keywords` | `keywords`（读取时会回退到旧键，用户自己维护的词表不会丢） |
| `hide_views` + `hide_view_ids` | 已移除 |
| `sticky_digg` / `debug_log` | 不变 |

三个旧布尔开关的默认值都是 `true`，合并后的 `hide_tips` 默认也是 `true`，
所以没手动改过的用户行为完全一致。

## 10. 工程结构

```
app/src/main/
├── kotlin/io/github/jjmj/douyinunlimit/
│   ├── App.kt                         Application，注册 XposedServiceHelper
│   ├── data/
│   │   ├── Prefs.kt                   键名 + 关键词解析（含旧键回退）
│   │   ├── ModuleSettings.kt          设置数据类（字段顺序 = 界面顺序）
│   │   └── SettingsBridge.kt          连框架的远程配置（Compose state）
│   ├── ui/                            Miuix 设置界面
│   │   ├── MainActivity.kt
│   │   ├── AppTheme.kt
│   │   └── SettingsScreen.kt          四个开关；关键词停顿 600ms 自动保存
│   └── xposed/
│       ├── HookEntry.kt               模块入口（java_init.list 指定）
│       ├── RuleSource.kt              热路径用的配置缓存（零分配）
│       ├── Diag.kt                    两级日志 + 内存缓冲 + 只追加
│       ├── ClickProbe.kt              点击探针（定位控件的利器）
│       ├── RestrictionGuard.kt        限制提示三合一（吐司 / 横幅 / 红叹号）
│       ├── TextHider.kt               关键词文字 + 压住重新显示的控件
│       └── LocalDigg.kt               点赞驳回后不回滚
└── resources/META-INF/xposed/         module.prop / scope.list / java_init.list
```

### v1.13 -> v1.14 的文件变化

| 删除 | 去处 |
|---|---|
| `ToastGuard.kt` `ImBanGuard.kt` `SendStatusGuard.kt` | 合并为 `RestrictionGuard.kt` |
| `TextGuard.kt` `BlockedViews.kt` | 合并为 `TextHider.kt` |
| `ViewGuard.kt` | id 黑名单删掉；`setVisibility` 压制并入 `TextHider.kt` |
| `NetGuard.kt` | 方案已证伪，整体删除 |
| —— | 新增 `RuleSource.textHidingOn()` 统一热路径节流 |

### 功耗设计（`RuleSource`）

配置会被 `setVisibility` / `setText` / `setText` 这类**每秒调用成千上万次**的热路径读取，
所以热路径上**不能有任何 I/O、时间调用或对象分配**：

- 所有配置缓存成 volatile 基本类型 / 数组，读取是纯内存访问
- 用**调用计数器**而不是 `System.nanoTime()` 做节流（时间调用远贵于计数器自增）
- **计数器刻意不用 `AtomicInteger`**：热点上 CAS 会跨线程争抢，而这里算错一两次
  只意味着「晚一点同步」，没有任何正确性影响，普通 `Int` 最便宜
- 每 1024 次热路径调用才真正读一次 SharedPreferences
- 命中判定是纯数组扫描，无装箱、无字符串分配
- `shouldBlockToast` 也改成读同一份缓存数组 —— v1.13 之前它每次调用都
  `Keywords.parse(prefs.getString(...))`，也就是**每条吐司**都做一次字符串
  split + List 分配 + 逐项 trim

于是未命中时每次调用只有：读一个 volatile + 计数器自增 + 一次数组扫描。

## 11. 一条贯穿全程的方法论

这份工作里最有价值的不是某个具体的 hook 点，而是这几条：

1. **先搞清楚「信息从哪来」，再造工具把它打出来。** 反复猜控件身份浪费的轮次最多；点击探针一上线就终结了它。
2. **静态分析给假设，真机日志给结论。** 「抖音不走 OkHttp」「父容器劫走触摸序列」「单击被判成双击」这三条关键结论全来自日志，靠猜永远得不到。
3. **不要在错误的抽象层里优化。** 拦点击这件事做不到「既原生又本地」，不是写法问题而是物理约束——早一点意识到就该换层（从拦点击换成拦回滚）。
4. **失败也要留下证据。** 每一次无效尝试都该在日志里区分开「没挂上 / 挂上没命中 / 命中但无效」，否则下一轮还是在猜。
5. **热路径的代价要算清楚。** 一个 setVisibility 上的装箱，乘上每秒上万次调用就是真实的功耗。

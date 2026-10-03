# 抖音伪装模块 —— 开发技术文档

> 记录整个开发过程中的**技术决策、逆向结论、踩过的坑和验证方法**。
> 目的：让后续维护者（包括未来的自己）不必重走一遍弯路。

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

抖音的类名通常稳定（`DuxToastV2`、`ChatBanTipsLogic`、`VideoDiggView`），但**方法名大量被 R8 混淆**（`LIZJ` / `LJ` / `LJFF`）。所以：

- 吐司：遍历所有「收 `CharSequence` 且返回 `void` 或引用类型」的方法挂 hook
- 点赞入口：找「三个参数、最后一个类型是 `kotlin.jvm.functions.Function2`」的方法
- 抖音动画入口：找「参数是 `(DiggAnimationView, boolean)` 且返回 `void`」的方法
- 计数器图标：从 `VideoDiggView` 的字段里找「类型实现了 `View.OnClickListener`」的那个

**这样即使抖音改名也照样工作。**

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

### 6.1 屏蔽限制类吐司 ✅

**链路**（`com.bytedance.dux.toast` = DUX Toast 体系）：

```
DuxToastV2.LIZJ(context, icon, iconTint, text, ..., style, ...)   ← 唯一收口点
    ├─ style == DuxCustom → new PopupToast(context, view)   // 自绘浮层（截图里屏幕中上部那种）
    └─ 否则               → new DuxSystemToast(context)     // 系统 Toast + setView
```

两条显示分支都从同一个方法分叉，所以拿它的 `CharSequence` 参数判断，命中即 `return null`，两种样式一起挡掉。旧版 `DuxToast` 结构相同，一并处理。

**控件 id（真机核对过）**：布局 `0x7f0d0d38`，容器 LinearLayout `0x7f0abf56`，文本 `0x7f0a8db2`，背景 `GradientDrawable #E6393B44`。

**另一个兜底**：`android.widget.Toast#show()`，覆盖不经过 DUX 的零散调用。

### 6.2 消息页封禁横幅 ✅

```
ChatBanTipsLogic (extends PriorityLogic)      // 整个类只服务这一条横幅
  LJLLLLLL()V
    banInfo   = LX/0xtl.LIZ()                    // 从 Keva "imBanInfoSp" 读本地缓存
    punishIds = banInfo?.LJ() ?: emptyList()
    shownIds  = IMKevaConfig 里已展示过的 id
    if (punishIds.isEmpty()) { LJLLL(); return }                     // 隐藏
    for (id in punishIds) if (id !in shownIds) { LJLLLL(); return }  // 显示
    LJLLL()                                                          // 隐藏

ChatBanTipsUI (extends RipsUI)                // 渲染
  a = DuxImageView (0x7f0a5e36, 铃铛)
  b = DuxTextView  (0x7f0ac401, 文字)          ← 真机 Layout Inspect 核对过
```

让 `LJLLLLLL()` 空操作即可。找不到该名字时退化为「屏蔽该类所有 public 无参 void 方法」——这个类只干一件事，全屏蔽也没副作用。

### 6.3 聊天里的红叹号 / 发送状态提示 ✅

```
LX/179c （基类，发送状态指示）        a: Message   b: ImageView
  LIZ()    -> setImageResource + setVisibility(VISIBLE)   // 显示图标
  LIZLLL() -> 隐藏
  LJ(m)    -> 消息状态变化时调 LIZ()（虚分派，会走到子类实现）

StatusIconWithText extends LX/179c    f: DmtTextView
  LIZ()  -> invoke-super LIZ()  然后设置并显示 f 的文字
  LJI()  -> 设置并显示 f 的文字
  LIZJ() -> 把图标和文字都藏起来，再调 LJI()
```

**只拦「显示」方法（`LIZ` / `LJI`），不动 `LIZLLL` / `LIZJ` 这些隐藏路径**，避免和原有状态机打架。

> **去重**：这个功能和「按 id 隐藏控件」默认列表里的 `0x7f0ab151` 指向**同一个控件**，属于重复。保留按类名这套（不依赖资源 id），id 列表默认清空，降级为手动兜底工具。

### 6.4 按关键词隐藏文字 ✅（通用解）

**为什么需要它**：服务端下发的封禁文案既不在 dex 也不在资源表，而且同一类提示散落在多处（会话里的系统消息、聊天里的提示行、列表里的横幅……），按控件 id 点名是打地鼠。

**做法（两层，缺一不可）**：

```
TextView.setText(CharSequence)
    └─ 命中关键词 → 登记进 BlockedViews(WeakHashMap) + 立即 GONE

View.setVisibility(int)
    └─ 控件在 BlockedViews 里 → 任何 VISIBLE 请求都改写成 GONE
```

**为什么必须两层**：调用方经常在 `setText` **之后**再补一次 `setVisibility(VISIBLE)` 把它显示回来：

```smali
LJI():
    textView.setText(...)            // setText 这里我们识别到并标记
    textView.setVisibility(VISIBLE)  // 紧接着又被显示回来 ← 必须靠第二层压住
```

**必须跳过 `EditText`** —— 否则用户自己在输入框打「封禁」两个字，输入框会当场消失。

### 6.5 按控件 id 隐藏（手动兜底工具）

- 拦 `View.setVisibility(int)`：命中黑名单的 id，把任何「显示」请求改写成 `GONE`
- 也拦 `ViewGroup.addView` / `LayoutInflater.inflate`：很多控件是 XML 声明、默认 VISIBLE、代码从不调 `setVisibility` 的，只拦 setVisibility 完全抓不到
- **默认列表为空**，只作为「找不到合适类名、只能点名」时的兜底（见图 §5.5 的复用风险）

---

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

### 7.5 最终方向：不拦点击，只拦「回滚」

让抖音完整走完它自己的一套（乐观 +1、原生动画、原生双击特效、发请求），只在最后那一步拦下来：

```
用户点赞
  → 抖音乐观 +1 + 播原生特效 + 发请求
  → 服务端驳回
  → GlobalDiggStateManager 广播事件 (LX/0tvw)
  → VideoDiggView.onEventDiggUpdate(...)   ← 【驳回后重刷 UI 的入口】
  → 图标与数字被刷回未点赞
```

`onEventDiggUpdate` 是 **EventBus 订阅方法，方法名没有被混淆**，比较稳定。跳过它，驳回后的重渲染就不发生。

**这个方案同时解决了暂停和特效两个问题**，因为点击完全没有被碰。

**待验证的风险**：如果回滚不是只走这一条路（比如某处直接改模型再刷新），那就拦不住。届时日志会显示钩子挂上了、但数字仍然回滚。

---

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

## 9. 已知限制与待办

### 限制

1. **本地点赞/拦截只在当下生效**：抖音的数据模型仍是「未点赞」（服务端驳回是事实），视图被 RecyclerView 回收重绑、或列表整体刷新时，图标会按模型刷回未点赞。除非把 aid 记下来在绑定时重新套用——需要定位「视图 ↔ aid」的映射，尚未实现。
2. **资源 id 是 aapt 打包时分配的**，抖音升级后可能变化。这也是为什么所有能按类名的功能都不依赖 id。
3. **无法拦截服务端请求**（TTNet/Cronet 原生栈），所以「让服务端真的接受点赞」做不到，只能改本地呈现。
4. 签名每次 CI 现场生成 → 覆盖安装需先卸载。要固定签名需配置 `KEYSTORE_BASE64` 等 secret。

### 待办

1. 验证 §7.5 的「只拦回滚」方案
2. 模块改名（候选：「无事发生」/「本账号一切正常」/「查无此封」/「风控未命中」/「幻觉」）
3. 本地点赞的跨重绑保持

---

## 10. 工程结构

```
app/src/main/
├── kotlin/io/github/jjmj/douyinunlimit/
│   ├── App.kt                         Application，注册 XposedServiceHelper
│   ├── data/
│   │   ├── Prefs.kt                   键名 + 关键词解析 + 控件 id 解析
│   │   ├── ModuleSettings.kt          设置数据类
│   │   └── SettingsBridge.kt          连框架的远程配置（Compose state）
│   ├── ui/                            Miuix 设置界面
│   │   ├── MainActivity.kt
│   │   ├── AppTheme.kt
│   │   └── SettingsScreen.kt
│   └── xposed/
│       ├── HookEntry.kt               模块入口（java_init.list 指定）
│       ├── RuleSource.kt              热路径用的配置缓存（零分配）
│       ├── Diag.kt                    两级日志 + 内存缓冲 + 只追加
│       ├── ClickProbe.kt              点击探针（定位控件的利器）
│       ├── ToastGuard.kt              吐司
│       ├── ImBanGuard.kt              消息页横幅
│       ├── SendStatusGuard.kt         聊天红叹号
│       ├── TextGuard.kt               按关键词隐藏文字
│       ├── ViewGuard.kt               按 id 隐藏（手动兜底）
│       └── LocalDigg.kt               点赞不回滚
└── resources/META-INF/xposed/         module.prop / scope.list / java_init.list
```

### 功耗设计（`RuleSource`）

配置会被 `setVisibility` / `setText` / `addView` 这类**每秒调用成千上万次**的热路径读取，所以热路径上**不能有任何 I/O、时间调用或对象分配**：

- 所有配置缓存成 volatile 基本类型 / 数组，读取是纯内存访问
- 用**调用计数器**而不是 `System.nanoTime()` 做节流（时间调用远贵于计数器自增）
- 每 1024 次热路径调用才真正去读一次 SharedPreferences
- 命中判定是纯数组扫描，无装箱、无字符串分配

---

## 11. 一条贯穿全程的方法论

这份工作里最有价值的不是某个具体的 hook 点，而是这几条：

1. **先搞清楚「信息从哪来」，再造工具把它打出来。** 反复猜控件身份浪费的轮次最多；点击探针一上线就终结了它。
2. **静态分析给假设，真机日志给结论。** 「抖音不走 OkHttp」「父容器劫走触摸序列」「单击被判成双击」这三条关键结论全来自日志，靠猜永远得不到。
3. **不要在错误的抽象层里优化。** 拦点击这件事做不到「既原生又本地」，不是写法问题而是物理约束——早一点意识到就该换层（从拦点击换成拦回滚）。
4. **失败也要留下证据。** 每一次无效尝试都该在日志里区分开「没挂上 / 挂上没命中 / 命中但无效」，否则下一轮还是在猜。
5. **热路径的代价要算清楚。** 一个 setVisibility 上的装箱，乘上每秒上万次调用就是真实的功耗。

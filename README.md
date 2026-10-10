# FuriHook

Android 本地日语 Ruby 注音 LSPosed 模块，包名 `dev.furihook`，版本 0.3.0。支持 TextView、已核实的 Compose 文本布局入口与 WebView HTML Ruby，使用随 APK 分发的 Kuromoji IPADIC 词典。全部文本处理在宿主进程本地执行。

## 工具链

| 项目 | 版本 |
| --- | --- |
| Java / Actions JDK | 17 / Eclipse Temurin 17 |
| Gradle Wrapper | 8.14.3，发行包 SHA-256 校验 |
| Android Gradle Plugin | 8.13.2 |
| Android SDK | minSdk 28，compileSdk 36，targetSdk 36 |
| SDK Build Tools | 35.0.0，AGP 默认版本 |
| libxposed | `io.github.libxposed:api:102.0.0`，`compileOnly` |
| 本地词典 | `com.atilika.kuromoji:kuromoji-ipadic:0.9.0` |
| RecyclerView / AndroidX Test | 1.4.0 / runner、core 1.7.0，JUnit extension 1.3.0 |
| Compose 测试应用 | Foundation、UI 1.10.6，Activity 1.10.1；Java 调用实际 JVM 方法 |

[AGP 官方兼容性说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)确认这组 JDK、Gradle 和 SDK 版本能够配合使用。模块只加载于实现 Modern API 102 的框架。

## 目录职责

| 目录 | 内容 |
| --- | --- |
| `app/` | 模块说明界面、Modern API 入口、TextView/Compose/WebView Hook、敏感输入保护、后台读音 |
| `app/src/main/resources/META-INF/xposed/` | Java 入口、模块版本、默认测试作用域 |
| `core/` | 纯 Java Unicode 检测、Kuromoji 读音、UTF-16 范围、真实词典语料验证 |
| `ruby/` | Android RubySpan、原地渲染、词典原始许可与 NOTICE |
| `testapp/` | 独立 Android 测试应用、真实 instrumentation E2E |
| `scripts/` | APK 元数据检查、模拟器 E2E、模块界面启动检查 |
| `.github/workflows/android.yml` | 云端构建、单元测试、Lint、API 28/36 模拟器测试、产物归档 |
| `docs/` | API 核实记录、第二阶段研发要求 |
| `work/` | 本地研究资料、下载产物与中间结果，已被 Git 忽略 |

## 当前功能

- `FuriHookModule` 使用 API 102 的无参数入口与生命周期回调。Android 29 及以上通过 `onPackageLoaded` 注册，Android 28 通过 `onPackageReady` 注册；注册器在同一进程只执行一次。
- Hook 公开的 `setText(CharSequence, BufferType)` 和 `setText(char[], int, int)`。常见字符串、资源与 `setTextKeepState` 调用经过前一个入口，字符数组调用经过后一个入口。
- 每次拦截执行原调用一次。公开二参入口对非输入的汉字候选文本选择 `BufferType.SPANNABLE`，随后只在当前文本对象添加 Ruby spans，原文字符保持原样。回调的候选预检最多 2048 UTF-16 code unit；词典与分词全部在后台执行。模块不调用第二次 `setText()`。
- 显式启用 `ExceptionMode.PROTECTIVE`，观测异常独立处理，宿主方法原有异常继续传播。
- 输入框、Editable、keyListener、非空 inputType、密码、转换控件与 PrecomputedText 跳过注音。系统服务、系统应用、系统 UID、模块自身跳过注册。
- `JapaneseDetector` 按 Unicode 18.0 已分配的 CJK Unified/Compatibility Ideographs 范围遍历 code point，支持补充平面、代理对和空值。包含 Han 字符只产生候选结果，语言状态为 `undetermined`。U+3007 等范围之外的表意字符不纳入该候选定义。
- `KuromojiReadingEngine` 使用词语读音；片假名转换为平假名，完整汉字词保留整词注音，送假名在唯一匹配时分离。未知读音与歧义跳过。
- `RubyTextRenderer` 添加真实 `ReplacementSpan`，测量注音宽度并扩展行高。原有 spans 保留；宿主 ReplacementSpan 冲突与词内样式边界跳过。超出内容区单行宽度的词段保留原文。
- Compose 在包 ClassLoader 就绪时验证 Android 文本转换方法和两种 Foundation 文本节点的实际签名。后台分析完成后使节点的布局缓存失效，重新测量时在 Android Layout 创建前添加 RubySpan。Compose 的原始 AnnotatedString、文本范围和语义保持完整；可编辑文本没有进入这些只读节点的适配上下文。
- WebView 注入本地 DOM 脚本，逐批读取文本节点，后台生成读音，再用 `<ruby><rb>原文</rb><rt>平假名</rt></ruby>` 替换匹配的文本节点。MutationObserver 处理动态页面，导航版本检查丢弃旧结果；父元素、链接与事件监听器保留。排除输入、contenteditable、脚本、样式和已有 Ruby；复制选区时移除模块自己的 rt。
- 三种适配共享一个本地词典实例，后台分析串行使用词典。`ReadingEngine`、`RubySegment`、`RubyRenderer`、`RubyStyle` 保持独立。

## 性能与隐私

回调只复制最多 2048 个 UTF-16 code unit，并保持代理对边界。词典初始化、分词和候选检测在专用后台线程执行。待处理视图与弱引用状态最多 256 项，每个视图保留最新快照，由一个后台任务依次处理；快速更新合并，结果应用前核实更新序号、文本对象与内容。内存缓存最多 64 条，候选日志与应用日志分别每秒最多 10 条；后台线程空闲 30 秒后退出。

日志含包名、进程名、View 类别、长度、候选结果、注音数量、耗时和截断信息，宿主原文输出长度固定为零。快照和缓存仅存在有界内存中，模块没有宿主文本文件持久化、网络权限或文本上传功能。异常日志只含异常类别。

Debug 构建默认启用检测日志，可使用以下命令关闭；Release 构建关闭检测日志。日志开关不会关闭本地注音。

```bash
./gradlew :app:assembleDebug -PfurihookDetectionLogs=false
```

## 构建与自动验证

优先使用仓库的 GitHub Actions。每次 push、pull request 或手动触发 `Android verification` 时，云端执行：

```bash
./gradlew :app:assembleDebug :testapp:assembleDebug :testapp:assembleDebugAndroidTest :core:test :core:verifyReadingCorpus :app:lintDebug :testapp:lintDebug
```

随后检查 APK 中的入口、模块属性、作用域、SDK、网络权限、八个词典数据文件与完整许可声明，确认 libxposed API 类没有被打包。API 28 和 36 的真实 Android 模拟器运行：

```bash
./gradlew :testapp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.furihook.testapp.MainActivityE2ETest,dev.furihook.testapp.RubyRendererE2ETest
```

14 个普通 E2E 验证原有文本、重载、输入与生命周期，以及真实词典、RubySpan、TextView Layout、Canvas 像素位置、富文本样式、点击、选择、长读音、动态文本和 RecyclerView 复用。云端还安装并启动模块说明界面，通过 UI XML 检查名称、版本和阶段内容。Hook 专项测试只在已经启用模块的 Windows 设备执行，读取实际 TextView/Compose Layout 的 RubySpan 与 WebView DOM，校验范围、读音、动态更新与交互。

Actions 保存 APK、JUnit XML/HTML、真实词典 TSV 语料报告、Lint、元数据与 SHA-256、模块界面 XML 和模拟器验证 JSON。这些产物支持重复执行与独立复查。

版本 0.2.0 的 [Actions 37945088658](https://github.com/kongjianguan/FuriHook/actions/runs/37945088658) 已通过：8 个核心测试、真实词典语料及 API 28/36 各 14 个 E2E。Windows API 35 的 Vector API 102 环境通过相同 14 个 E2E 和 1 个真实 Hook 测试，APK 校验与设备证据见 [设备验证](docs/DEVICE_VALIDATION.md)。

本机构建需要 JDK 17 和 Android SDK 36；通过 `ANDROID_HOME` 或未跟踪的 `local.properties` 指定 SDK。

APK 标准输出路径：

- `app/build/outputs/apk/debug/app-debug.apk`
- `testapp/build/outputs/apk/debug/testapp-debug.apk`
- `testapp/build/outputs/apk/androidTest/debug/testapp-debug-androidTest.apk`

## Windows 远程模拟器

`scripts/start_windows_emulator.ps1` 使用 Windows 上的官方 Android Emulator、WHPX 和 `system-images;android-35;default;x86_64` 创建独立的 `FuriHook_API35` 虚拟设备。脚本检查加速能力，使用端口 5580，通过计划任务运行 `windows_emulator_worker.ps1` 并等待启动完成。计划任务需要 Windows 用户已经登录。SDK、Java 和工作目录可以通过参数指定；`-Ramdisk` 可以指定独立的启动镜像副本；现有虚拟设备保持原状。

将云端构建的三个 APK 放入 `D:\workspace\furihook-validation`，执行：

```powershell
pwsh -NoProfile -File scripts/start_windows_emulator.ps1
pwsh -NoProfile -File scripts/provision_windows_apks.ps1
pwsh -NoProfile -File scripts/test_windows_emulator.ps1
```

测试脚本安装 APK，执行相同的普通 instrumentation 场景，检查模块界面 XML，并保存 APK SHA-256、测试输出和验证 JSON 到工作目录的 `results/`。完成后可通过 SDK 中的 `adb -s emulator-5580 emu kill` 关闭该测试设备。

跨云端构建的 Debug 签名独立。`provision_windows_apks.ps1` 只允许操作 `FuriHook_API35`，重新安装本项目的三个测试包，再启用模块和默认测试作用域；它清除专用设备上这些包的数据。

专用设备已经安装 [Magisk v30.7](https://github.com/topjohnwu/Magisk/releases/tag/v30.7) 与 [Vector v2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2)。Vector 属于 LSPosed 系谱的独立项目，实现 libxposed API 102。`scripts/verify_windows_hooks.ps1` 检查框架、作用域，运行普通 E2E 和独立 Hook 注音测试，再检查初始化、注册、检测和注音事件。实际结果及复验方法见 [设备验证](docs/DEVICE_VALIDATION.md)。

## LSPosed 验证

1. 安装两个 Debug APK。
2. 在支持 libxposed API 102 的 LSPosed 管理器中启用 FuriHook。
3. 选择默认作用域 `dev.furihook.testapp`，终止并重新启动测试应用。
4. 通过框架日志或 `adb logcat -s FuriHook:I` 检查 `module_loaded`、`hook_registered`、`text_observed` 和 `ruby_applied`。Vector v2.2 的 Modern API 日志写入 Logcat。
5. 更新动态文本，滚动列表，点击富文本并选择文字，确认宿主显示与交互保持正常。密码与输入框不应产生文本观测。

作用域通过 LSPosed 管理器选择，`staticScope=false` 允许选择其他普通应用。本阶段跳过系统应用。启用作用域或更新 APK 后需要重新启动目标进程；自动热重载关闭。

云端模拟器验证覆盖安装、界面与测试应用运行；Windows 的 API 35 专用模拟器同时验证 Vector API 102 的实际注入与 Hook 日志。框架版本及厂商 Android 差异需要在相应设备上验证。模块界面不提供激活状态。

## 覆盖边界

自定义 TextView 子类完全覆盖目标方法且不调用父类时，基类 Hook 无法处理更新。Editable、PrecomputedText、转换控件和自定义 Canvas 绘制跳过注音。字符数组调用只有现有 Spannable 缓冲支持原地注音；模块不改变该入口的原始参数。

Compose 适配核实了 1.10.6 的内部类和 JVM 方法签名。宿主对这些内部类或方法执行混淆、移除，或使用不同签名时，日志报告 `compose_unsupported`；此时无法保证该宿主文本注音。X 12.31.0 的 APK 和实际页面尚未在测试设备验证。WebView 适配处理当前主文档，iframe、Shadow DOM、关闭 JavaScript 的页面和浏览器自定义内核需要独立验证；过长文本节点仅分析有界前缀。

超过 2048 UTF-16 code unit 的尾部不会分析，`truncated` 明示该情况。视图状态或待处理集合达到 256 项上限时清理已有状态，后续文本更新重新建立状态。候选检测无法区分日语、中文、韩文中的 Han 字符；IPADIC 的歧义读音、专有名词和新词存在准确率限制。

ReplacementSpan 将覆盖范围作为原子排版单位，词内光标粒度会减少，完整词不能跨行拆分。布局变化会重新检查可用宽度与字号；其他字体属性或词内样式在注音完成后发生变化时，需要额外验证。排版行为和保护规则见 [Ruby 渲染](docs/RUBY_RENDERER.md)，后台生命周期见 [TextView 管线](docs/TEXTVIEW_PIPELINE.md)。

已经携带 FuriHook RubySpan 的文本保留现有注音。宿主直接修改同一 Spannable 的字符或复用带有旧注音的文本时，需要独立的变化监听与注音失效处理；当前管线以被 Hook 的文本设置调用建立分析任务。

API 核实依据及官方资料访问状态见 [API 核实记录](docs/API_VERIFICATION.md)。后续研发要求见 [后续研发](docs/PHASE_TWO.md)。

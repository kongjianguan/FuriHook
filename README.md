# FuriHook

Android 本地日语 Ruby 注音 LSPosed 模块，包名 `dev.furihook`。当前开发阶段为 TextView 只读文本观测与 Han 候选检测。日语词法分析、读音生成和注音渲染仅定义接口。

## 工具链

| 项目 | 版本 |
| --- | --- |
| Java / Actions JDK | 17 / Eclipse Temurin 17 |
| Gradle Wrapper | 8.14.3，发行包 SHA-256 校验 |
| Android Gradle Plugin | 8.13.2 |
| Android SDK | minSdk 28，compileSdk 36，targetSdk 36 |
| SDK Build Tools | 35.0.0，AGP 默认版本 |
| libxposed | `io.github.libxposed:api:102.0.0`，`compileOnly` |
| RecyclerView / AndroidX Test | 1.4.0 / runner、core 1.7.0，JUnit extension 1.3.0 |

[AGP 官方兼容性说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)确认这组 JDK、Gradle 和 SDK 版本能够配合使用。模块只加载于实现 Modern API 102 的框架。

## 目录职责

| 目录 | 内容 |
| --- | --- |
| `app/` | 模块说明界面、Modern API 入口、TextView Hook、敏感输入保护、后台观测与限流 |
| `app/src/main/resources/META-INF/xposed/` | Java 入口、模块版本、默认测试作用域 |
| `core/` | 纯 Java Unicode 检测、读音数据结构、渲染及 Compose/WebView 接口、单元测试 |
| `testapp/` | 独立 Android 测试应用、真实 instrumentation E2E |
| `scripts/` | APK 元数据检查、模拟器 E2E、模块界面启动检查 |
| `.github/workflows/android.yml` | 云端构建、单元测试、Lint、API 28/36 模拟器测试、产物归档 |
| `docs/` | API 核实记录、第二阶段研发要求 |
| `work/` | 本地研究资料、下载产物与中间结果，已被 Git 忽略 |

## 当前功能

- `FuriHookModule` 使用 API 102 的无参数入口与生命周期回调。Android 29 及以上通过 `onPackageLoaded` 注册，Android 28 通过 `onPackageReady` 注册；注册器在同一进程只执行一次。
- Hook 公开的 `setText(CharSequence, BufferType)` 和 `setText(char[], int, int)`。常见字符串、资源与 `setTextKeepState` 调用经过前一个入口，字符数组调用经过后一个入口。
- 每次拦截原样调用 `Chain.proceed()` 一次，完成后读取当前 `getText()`。模块没有任何写入宿主文本、递归调用 `setText()` 或替换业务参数的路径。
- 显式启用 `ExceptionMode.PROTECTIVE`，观测异常独立处理，宿主方法原有异常继续传播。
- 输入框、Editable、keyListener、非空 inputType、标准密码类型与密码转换控件跳过检测。系统服务、系统应用、系统 UID、模块自身跳过注册。
- `JapaneseDetector` 按 Unicode 18.0 已分配的 CJK Unified/Compatibility Ideographs 范围遍历 code point，支持补充平面、代理对和空值。包含 Han 字符只产生候选结果，语言状态为 `undetermined`。U+3007 等范围之外的表意字符不纳入该候选定义。
- `ReadingEngine`、`RubySegment`、`RubyRenderer`、`RubyStyle`、`ComposeAdapter` 和 `WebViewAdapter` 保持独立。没有词典和渲染实现。

## 性能与隐私

回调只复制最多 2048 个 UTF-16 code unit，并保持代理对边界；候选检测和 JSON 日志在单独线程执行。每个 View 最多每 500 ms 采样一次，同一不可变 String 对象重复设置会跳过。进程每秒最多接收 40 个快照、输出 10 条日志，队列最多 16 项，视图弱引用状态最多 256 项。队列满载和高频调用会跳过采样，后台线程空闲 30 秒后退出。

日志含包名、进程名、View 类别、长度、候选结果、截断标记和跳过计数，宿主原文输出长度固定为零。临时快照仅存在内存中，模块没有文件持久化、网络权限或文本上传功能。异常日志只含异常类别。

Debug 构建默认启用检测日志，可使用以下命令关闭；Release 构建关闭检测日志。

```bash
./gradlew :app:assembleDebug -PfurihookDetectionLogs=false
```

## 构建与自动验证

优先使用仓库的 GitHub Actions。每次 push、pull request 或手动触发 `Android verification` 时，云端执行：

```bash
./gradlew :app:assembleDebug :testapp:assembleDebug :testapp:assembleDebugAndroidTest :core:test :app:lintDebug :testapp:lintDebug
```

随后检查 APK 中的 `java_init.list`、`module.prop`、`scope.list`、入口类、SDK 版本和网络权限，确认 libxposed API 类没有被打包。API 28 和 36 的真实 Android 模拟器运行：

```bash
./gradlew :testapp:connectedDebugAndroidTest
```

6 个 E2E 场景验证样例文本及重载、按钮更新、ClickableSpan 点击与选择、RecyclerView 滚动绑定、普通与密码输入、Activity 停止/恢复周期更新。云端还安装并启动模块说明界面，通过 UI XML 检查名称、版本和阶段内容。测试使用真实 Android 控件与生命周期。

Actions 保存 `debug-apks-and-reports`、`e2e-api-28`、`e2e-api-36`：包括 APK、JUnit XML/HTML、Lint、元数据与 SHA-256、模块界面 XML 和模拟器验证 JSON。这些产物支持重复执行与独立复查。

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
pwsh -NoProfile -File scripts/test_windows_emulator.ps1
```

测试脚本安装 APK，执行相同的六个 instrumentation 场景，检查模块界面 XML，并保存 APK SHA-256、测试输出和验证 JSON 到工作目录的 `results/`。完成后可通过 SDK 中的 `adb -s emulator-5580 emu kill` 关闭该测试设备。

专用设备已经安装 [Magisk v30.7](https://github.com/topjohnwu/Magisk/releases/tag/v30.7) 与 [Vector v2.2](https://github.com/JingMatrix/Vector/releases/tag/v2.2)。Vector 属于 LSPosed 系谱的独立项目，实现 libxposed API 102。`scripts/verify_windows_hooks.ps1` 检查框架 API、模块启用状态与作用域，在真实 Hook 状态下运行六个应用测试，再检查模块初始化、一次 Hook 注册、四种文本候选结果、输入保护和日志原文长度。实际结果及复验方法见 [设备验证](docs/DEVICE_VALIDATION.md)。

## LSPosed 验证

1. 安装两个 Debug APK。
2. 在支持 libxposed API 102 的 LSPosed 管理器中启用 FuriHook。
3. 选择默认作用域 `dev.furihook.testapp`，终止并重新启动测试应用。
4. 通过框架日志或 `adb logcat -s FuriHook:I` 检查 `module_loaded`、`hook_registered` 和 `text_observed`。Vector v2.2 的 Modern API 日志写入 Logcat。
5. 更新动态文本，滚动列表，点击富文本并选择文字，确认宿主显示与交互保持正常。密码与输入框不应产生文本观测。

作用域通过 LSPosed 管理器选择，`staticScope=false` 允许选择其他普通应用。本阶段跳过系统应用。启用作用域或更新 APK 后需要重新启动目标进程；自动热重载关闭。

云端模拟器验证覆盖安装、界面与测试应用运行；Windows 的 API 35 专用模拟器同时验证 Vector API 102 的实际注入与 Hook 日志。框架版本及厂商 Android 差异需要在相应设备上验证。模块界面不提供激活状态。

## 覆盖边界

自定义 TextView 子类完全覆盖目标方法且不调用父类时，基类 Hook 无法观察更新。Editable 的原地修改、Compose 文本、WebView DOM 和自定义 Canvas 绘制需要独立适配。

观测采用有限采样，超过 2048 UTF-16 code unit 的尾部不会检测，日志中的 `truncated` 明示该情况。限流期间的更新可能被跳过，因此日志无法作为完整的文本更新审计。候选检测无法区分日语、中文、韩文中的 Han 字符。

API 核实依据及官方资料访问状态见 [API 核实记录](docs/API_VERIFICATION.md)。后续研发要求见 [第二阶段](docs/PHASE_TWO.md)。

# 设备验证

## 云端构建

[GitHub Actions 38149087114](https://github.com/kongjianguan/FuriHook/actions/runs/38149087114) 对提交 `d5912ed666e88542bcb9d49a6630950cd6041e0e` 执行验证，版本为 FuriHook 0.4.0。所有 workflow job 均成功：

- `:app:assembleDebug`、`:testapp:assembleDebug`、`:testapp:assembleDebugAndroidTest` 成功。
- 8 个核心测试通过；真实 Kuromoji IPADIC 语料报告包含 21 个注音段和 3 个按语料规则跳过读音的段落。
- API 28 与 API 36 各执行 14 个 instrumentation E2E，失败、错误和跳过均为零。
- 测试覆盖真实读音与 UTF-16 范围、RubySpan、TextView Layout、Canvas 像素、粗体、上标、富文本点击、文字选择、动态更新和 RecyclerView 复用。
- 两个 Android 模块的 Lint 均为零错误；app 有 6 条警告，testapp 有 12 条警告。
- Modern API 102 入口、属性、作用域、WebView 脚本和 APK DEX 定义检查通过。API 实现类没有打包进 APK，APK 没有旧版入口或网络权限。
- 八个词典数据文件及原始 LICENSE/NOTICE 校验通过；词典文件解压后合计 33,426,790 字节。
- 两个云端模拟器均成功安装并启动模块说明界面，UI XML 中的名称、版本与阶段检查通过。
- Chromium 149.0.7827.55 与 React 18.3.1 浏览器验证通过：生产 TextNode 身份保持，240 段批量更新均完成分析，23 个 Ruby 可见，像素采样得到 205 个 Ruby 墨迹像素，无浏览器错误。分数缩放 `matrix(1.137, 0, 0, 1.137, 0, 0)` 下两条注音在安静等待前后保持稳定；host CSS 变更在读音消失后保留，祖先裁剪内容不扩展空间。
- 云端模拟器未安装 LSPosed，`hookExecutionVerified=false`；真实框架注入由 Windows Vector 设备验证。

工具链使用 Java 17、Gradle 8.14.3、AGP 8.13.2、compileSdk/targetSdk 36、minSdk 28 和 Build Tools 35.0.0。Hook 依赖为 `io.github.libxposed:api:102.0.0`，词典依赖为 `com.atilika.kuromoji:kuromoji-ipadic:0.9.0`。

## Windows API 35

通过 `he@100.90.130.99` 连接 `LAPTOP-MISAKA`，使用已有官方 Android SDK 创建独立设备：

| 项目 | 实际配置 |
| --- | --- |
| 系统 | Windows 11，10.0.26200 |
| 模拟器 | Android Emulator 35.5.10.0，WHPX 可用 |
| 镜像 | 官方 AOSP `system-images;android-35;default;x86_64`，revision 2 |
| 虚拟设备 | `FuriHook_API35`，`emulator-5580` |
| 工作目录 | `D:\workspace\furihook-validation` |
| 运行方式 | `FuriHook-Emulator-5580` 计划任务，Windows 用户保持登录 |
| Root | Magisk v30.7，Zygisk 启用 |
| Hook 框架 | Vector v2.2，versionCode 3080，CLI 实际报告 API 102 |
| 注入作用域 | `dev.furihook.testapp/0` |

Magisk 官方 `build.py avd_patch` 从 SDK 原始 ramdisk 生成独立副本；原始文件 SHA-256 保持一致。设备通过 `-ramdisk` 指定副本冷启动。工具来自 [Magisk 官方发行版](https://github.com/topjohnwu/Magisk/releases/tag/v30.7)和 [Vector 官方发行版](https://github.com/JingMatrix/Vector/releases/tag/v2.2)。

2026-10-12 使用与 Actions 38149087114 完全匹配的三个 APK 执行 `scripts/verify_windows_hooks.ps1`，模块 SHA-256 为 `3bb955522e7a7e6e625decd7e186317956164c2d2c5d98b2ef19b6347dba6fda`。验证通过以下检查：

- 安装模块、测试应用和 instrumentation APK；检查模块说明界面的 UI XML。
- 模块启用期间，14 个普通 E2E 与 3 个真实 Hook E2E 全部通过。TextView 与 Compose 文本布局中的 RubySpan 来自模块 ClassLoader；DOM 注音来自实际 WebView 页面。
- 日语、混合与长文本得到注音；富文本点击与文字选择保持可用；动态更新和 RecyclerView 复用得到当前文本的读音。
- 普通 CharSequence、Spannable、SpannedString 与已有 Spannable 缓冲的字符数组调用通过验证。PrecomputedText、输入、密码和超宽字号样例保持保护规则。
- 两个公开 TextView 方法各注册一次。Hook 报告给出 12 次文本观测和 11 次注音应用；敏感输入观测为 false，原文日志长度为零。
- 日语样例报告四个注音范围；日语与混合样例含有 Han 候选，纯平假名与英文没有候选。敏感输入没有进入观测日志，宿主原文日志长度为零。

WebView 专项验证真实 React 原文与译文切换、原始 TextNode 身份、列表更新和删除、动态文本、链接点击、输入与 contenteditable 排除以及现有 Ruby 保留。340 个文本节点与新增 240 段完成扫描，待处理读音队列归零。1800 字符前缀后的横向滚动达到约 21,510 CSS px，屏幕内四个 Ruby 均通过 Range 和裁剪检查。PixelCopy 检查实际注音层，读音区域包含 510 个墨迹像素。注音位于独立 closed ShadowRoot，不替换宿主文本。

Compose 专项读取真实 BasicText 的 AndroidParagraph 文本与布局缓存，验证普通字符串、AnnotatedString、链接触摸、11 行长文本、纯英文与假名排除及动态更新。两个段落分别设置 24sp 和 26sp 行高，各含两个 Ruby 范围，链接点击一次成功。

更新样例 `明日は図書館へ行きます。` 包含 `明日→あした`、`図書館→としょかん`、`行→い`；最后一个词元的完整读音为 `いき`，送假名保留在正文。

实际验证的样例 `今日は学校で日本語を勉強します。` 包含四个 Ruby 范围：

| UTF-16 半开区间 | 原文 | 平假名 |
| --- | --- | --- |
| `[0, 2)` | 今日 | きょう |
| `[3, 5)` | 学校 | がっこう |
| `[6, 9)` | 日本語 | にほんご |
| `[10, 12)` | 勉強 | べんきょう |

## APK 校验

云端 APK 检查报告、Windows 安装产物与本地标准输出目录中的 SHA-256 一致：

| APK | SHA-256 |
| --- | --- |
| `app-debug.apk` | `3bb955522e7a7e6e625decd7e186317956164c2d2c5d98b2ef19b6347dba6fda` |
| `testapp-debug.apk` | `7de59078d5e5c5901aaf2059efdaf58b83809389a45b0b87d790256e940ed528` |
| `testapp-debug-androidTest.apk` | `01fdd6de47a99a0ab56587d6cbfac6b4720d98ecf903e58140566b0eba156c84` |

APK 校验报告确认入口为 `dev.furihook.hook.FuriHookModule`，默认作用域为 `dev.furihook.testapp`，Modern API 最低与目标版本均为 102；无旧版入口且未将 API 实现类打包。云端 APK 位于 `work/artifacts/run-38149087114/`，Windows 设备证据位于 `work/windows-validation/run-38149087114/`。三份 APK 的云端、Windows 安装目录及仓库标准输出目录 SHA-256 完全一致。

## Via 浏览器

同一 APK 在实际 Via 7.3.3（`mark.via.gp`）执行 `scripts/verify_windows_via.ps1` 通过。页面使用本地 HTTP 服务器提供的 `scripts/fixtures/via-ruby.html`，验证读取真实 Via Activity 的 UI 与 DOM 报告，并执行真实触摸：

- 初始正文的四处注音读音正确。
- 点击更新按钮后得到三个当前文本的注音。
- 含日文的链接触发一次原有点击处理器。
- 输入与可编辑区域排除，已有 Ruby 保留。
- 原文有四组正确的 Ruby，更新后有三组正确的 Ruby；四组与三组注音都在实际页面可见，布局清晰。
- React 译文与原文节点的身份和父节点保持，原文可切回；列表节点保留、移除和插入符合预期。
- Via 进程记录四次 `webview_ruby_applied`，计数为 8、17、12、14；没有失败事件。可编辑区域排除、已有 Ruby 保留、链接点击一次成功。

脚本恢复默认测试作用域并移除端口反向转发。Via JSON、页面 XML 与模块日志位于 `work/windows-validation/run-38149087114/via/`。

## X 12.31.0 ARM64 Hook 验证

X 真实进程 Hook 验证使用 Apple M5/16 GB、macOS 27.0.1、Android Emulator 37.2.12（HVF）与官方 Google APIs API 35 arm64-v8a revision 9，设备为 `emulator-5586`。Magisk 30.7 已对该模拟器执行真实 ramdisk patch 并启用 Zygisk；Vector 2.2（versionCode 3080）实际报告 Modern API 102。安装的 FuriHook APK 与 Actions 38149087114 的 `d5912ed` 产物 SHA-256 一致：`3bb955522e7a7e6e625decd7e186317956164c2d2c5d98b2ef19b6347dba6fda`。

用户提供的 X 12.31.0-prod.01（`com.twitter.android`，versionCode `312310001`，APK set SHA-256 `03eb62077992827bb79fc2cec582db2734dc035b9bf9c6fffbfa456296ecf073`）安装六个 split。验证脚本从设备取回模块 APK 与 X 的 base、arm64、日文 split，并确认各文件 SHA-256 与源文件相同。`scripts/verify_x_arm64_vector.sh` 的现有设备与关闭后冷启动验证均通过，脚本 SHA-256 为 `49ae02b2b3509511251e8caf390618c365c4cc1d32a9f0ebe2f44e2a01f4e224`。

真实 X 进程日志确认 Vector 加载一次、Zygisk Binder bridge 记录 24 个事件、模块以 API 102 加载，并成功注册 TextView、WebView 与 X 专用 Compose Hook。X Compose 产生四条 `x_compose_ruby_applied` 事件，读音段计数为 1、2、3、5；事件在真实 Spannable 和 intrinsic 缓存写入后记录。日语区域下的主界面 UI XML 含有「いま何が起こっているかチェック」与「電話番号で続ける」。验证期间网络状态为 `VALIDATED`，观察到 X 的 HTTPS 连接，未登录，未出现 AndroidRuntime 崩溃。

本次确认的是 X 主界面真实 Compose 节点的 RubySpan 应用。未登录，因此没有验证帖子正文；没有执行 X 页面独立像素测量，X WebView 注册成功但未观测到 Ruby 应用事件。可复核报告、运行日志与 UI XML 位于 `work/native-arm64-validation/final-x-vector/20261012-190134/`。

在已准备好 SDK、隔离 ramdisk、Vector 与测试 APK 的专用 ARM64 环境中，可以重复执行 `bash scripts/verify_x_arm64_vector.sh`。脚本检查实际安装文件的 SHA-256、运行中的模拟器参数、框架事件、X 主界面与崩溃日志；设备关闭时会启动专用虚拟机，任何必需检查失败都会终止。

## 重复验证

保持专用虚拟设备运行，将三个云端 APK 放入 Windows 工作目录，并执行仓库脚本：

```powershell
pwsh -NoProfile -File scripts/provision_windows_apks.ps1
pwsh -NoProfile -File scripts/verify_windows_hooks.ps1
```

重新安装脚本仅允许使用 `FuriHook_API35`，只清除本项目三个测试包的数据，并恢复模块启用与默认作用域。验证脚本要求已安装 Vector API 102；任何检查失败都会终止。

Windows 工作目录 `results/` 保存 instrumentation 输出、模块界面 XML、框架与作用域 JSON、模块事件与 APK SHA-256。专项证据包括 `webview-ruby-e2e.json`、`compose-ruby-e2e.json`、`webview-ruby-pixel-copy.png`、`compose-simple-ruby-pixels.png` 与 `ruby-adapter-logcat.txt`。普通应用测试报告的 `hookExecutionChecked=false` 表示只记录应用测试；实际注入验证由 `hook-verification.json` 的 `hookExecutionVerified=true` 给出。

验证范围包含云端 API 28/36 的普通 Android 运行、Windows API 35 的 Vector 注入与实际 Via 页面，以及 Apple M5 Google APIs API 35 arm64-v8a 模拟器中的 X 12.31.0 Vector API 102 进程 Hook。X 主界面的真实 Compose RubySpan 写入已验证；帖子正文、独立像素和 X WebView Ruby尚未验证。其他 Compose 内部签名、被混淆的宿主、厂商 Android 和物理设备需要分别验证。当前覆盖边界见 README，后续研发要求见 `PHASE_TWO.md`。

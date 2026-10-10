# 设备验证

## 云端构建

[GitHub Actions 38037040758](https://github.com/kongjianguan/FuriHook/actions/runs/38037040758) 对应用代码提交 `5fe860054299fe4cca90733752eab89213cde1a8` 执行验证，版本为 FuriHook 0.3.0：

- `:app:assembleDebug`、`:testapp:assembleDebug`、`:testapp:assembleDebugAndroidTest` 成功。
- 8 个核心单元测试及真实 Kuromoji IPADIC 语料验证通过。
- API 28 与 API 36 各执行 14 个 instrumentation E2E，失败、错误和跳过均为零。
- 测试覆盖真实读音与 UTF-16 范围、RubySpan、TextView Layout、Canvas 像素、粗体、上标、富文本点击、文字选择、动态更新和 RecyclerView 复用。
- 两个 Android 模块的 Lint 没有错误。
- Modern API 102 入口、属性、作用域、WebView 脚本和 APK DEX 定义检查通过。API 实现类没有打包进 APK，APK 没有旧版入口或网络权限。
- 八个词典数据文件及原始 LICENSE/NOTICE 校验通过；词典文件解压后合计 33,426,790 字节。
- 两个云端模拟器均成功安装并启动模块说明界面，UI XML 中的名称、版本与阶段检查通过。

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

2026-10-10 使用上述云端产物执行 `scripts/verify_windows_hooks.ps1`，该脚本成功完成以下检查：

- 安装模块、测试应用和 instrumentation APK；检查模块说明界面的 UI XML。
- 模块启用期间，6 个应用 E2E 与 8 个 Ruby 渲染 E2E 全部通过。
- `RubyHookE2ETest`、`RubyWebViewHookE2ETest` 和 `RubyComposeHookE2ETest` 三个真实 Hook 测试通过。文本布局中的 RubySpan 来自模块 ClassLoader；DOM 注音来自实际 WebView 页面。
- 日语、混合与长文本得到注音；富文本点击与文字选择保持可用；动态更新和 RecyclerView 复用得到当前文本的读音。
- 普通 CharSequence、Spannable、SpannedString 与已有 Spannable 缓冲的字符数组调用通过验证。PrecomputedText、输入、密码和超宽字号样例保持保护规则。
- 重新启动目标进程后，两个公开 TextView 方法完成一次注册。日志包含 `module_loaded`、`hook_registered`、`text_observed` 和 `ruby_applied`，没有失败事件。
- 日语样例报告四个注音范围；日语与混合样例含有 Han 候选，纯平假名与英文没有候选。敏感输入没有进入观测日志，宿主原文日志长度为零。

WebView 专项验证静态文本、真实链接触摸、动态更新、contenteditable 与密码排除、已有 Ruby 保留和系统剪贴板中的原文。单个文本节点的 1800 字符 ASCII 前缀后，两个汉字词得到正确注音；340 个文本节点与一次新增 240 个段落全部完成扫描，最终变更队列和待处理节点均为空。重新加载后的注入代次从 2 增加到 4，新页面得到两个注音。

Compose 专项读取真实 BasicText 的 AndroidParagraph 文本与布局缓存，验证普通字符串、AnnotatedString、链接触摸、11 行长文本、纯英文与假名排除及动态更新。两个段落分别设置 24sp 和 26sp 行高，四个词段均得到注音，行顶空间检查通过。PixelCopy 从实际窗口读取 `学校` 样例，基础字体上方的检测区域包含 362 个非白色像素，PNG 与数值保存在设备证据中。

更新样例 `明日は図書館へ行きます。` 包含 `明日→あした`、`図書館→としょかん`、`行→い`；最后一个词元的完整读音为 `いき`，送假名保留在正文。

实际验证的样例 `今日は学校で日本語を勉強します。` 包含四个 Ruby 范围：

| UTF-16 半开区间 | 原文 | 平假名 |
| --- | --- | --- |
| `[0, 2)` | 今日 | きょう |
| `[3, 5)` | 学校 | がっこう |
| `[6, 9)` | 日本語 | にほんご |
| `[10, 12)` | 勉強 | べんきょう |

## 产物校验

云端 APK 检查报告、Windows 安装产物与本地标准输出目录中的 SHA-256 一致：

| APK | SHA-256 |
| --- | --- |
| `app-debug.apk` | `cdebebe5339ab30e340dcc2d6839fe07ee00885c9cecf2fed7452bf8e134997a` |
| `testapp-debug.apk` | `5b2fc557f6befcea14cdd218e599db2f883128b3f298d363f160f09be01936cb` |
| `testapp-debug-androidTest.apk` | `8439c437e4deeaa5452b03b432f225d2c02bc22feeae32d3f538c1672e2bb4a4` |

本地云端证据保存在被 Git 忽略的 `work/artifacts/run-38037040758/`，设备证据保存在 `work/windows-validation/run-38037040758/results/`。APK 保存在 README 指定的标准输出目录。E2E 报告中的原文仅来自固定测试语料。

## Via 浏览器

同一 APK 在实际 Via 7.3.3（`mark.via.gp`）执行 `scripts/verify_windows_via.ps1` 通过。页面使用本地 HTTP 服务器提供的 `scripts/fixtures/via-ruby.html`，测试通过 UIAutomator XML 读取实际 DOM 报告并执行真实触摸：

- 初始正文得到四个正确的 `<ruby>` 读音。
- 点击更新按钮后得到三个当前文本的注音。
- 含日文的链接触发一次原有点击处理器。
- 输入与可编辑区域排除，已有 Ruby 保留。
- Via 进程记录两次 `webview_ruby_applied`，计数分别为 14 与 3；日志没有失败事件。

脚本恢复默认测试作用域并移除端口反向转发。Via JSON、页面 XML 与模块日志位于设备证据目录的 `via/`。

## 重复验证

保持专用虚拟设备运行，将三个云端 APK 放入 Windows 工作目录，并执行仓库脚本：

```powershell
pwsh -NoProfile -File scripts/provision_windows_apks.ps1
pwsh -NoProfile -File scripts/verify_windows_hooks.ps1
```

重新安装脚本仅允许使用 `FuriHook_API35`，只清除本项目三个测试包的数据，并恢复模块启用与默认作用域。验证脚本要求已安装 Vector API 102；任何检查失败都会终止。

Windows 工作目录 `results/` 保存 instrumentation 输出、模块界面 XML、框架与作用域 JSON、模块事件与 APK SHA-256。专项证据包括 `webview-ruby-e2e.json`、`compose-ruby-e2e.json`、`compose-simple-ruby-pixels.png` 与 `ruby-adapter-logcat.txt`。普通应用测试报告的 `hookExecutionChecked=false` 表示只记录应用测试；实际注入验证由 `hook-verification.json` 的 `hookExecutionVerified=true` 给出。

验证范围包含云端 API 28/36 的普通 Android 运行，以及 Windows API 35 的 Vector 注入与实际 Via 页面。用户的 Vector 2.2（2080）、X 12.31.0-prod.01（312310001）、其他 Compose 内部签名、被混淆的宿主、厂商 Android 和物理设备尚未执行验证。作用域选择不会保证每一种文本绘制实现都能得到注音；当前覆盖边界见 README，后续研发要求见 `PHASE_TWO.md`。

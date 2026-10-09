# 设备验证

## 云端构建

[GitHub Actions 37945088658](https://github.com/kongjianguan/FuriHook/actions/runs/37945088658) 对应用代码提交 `96552d5fc4046d7ca0b1b2ecb990810da0e201f9` 执行验证，版本为 FuriHook 0.2.0：

- `:app:assembleDebug`、`:testapp:assembleDebug`、`:testapp:assembleDebugAndroidTest` 成功。
- 8 个核心单元测试及真实 Kuromoji IPADIC 语料验证通过。
- API 28 与 API 36 各执行 14 个 instrumentation E2E，失败、错误和跳过均为零。
- 测试覆盖真实读音与 UTF-16 范围、RubySpan、TextView Layout、Canvas 像素、粗体、上标、富文本点击、文字选择、动态更新和 RecyclerView 复用。
- 两个 Android 模块的 Lint 没有错误。
- Modern API 102 入口、属性、作用域和 APK DEX 定义检查通过。API 实现类没有打包进 APK，APK 没有旧版入口或网络权限。
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

2026-10-09 使用上述云端产物执行 `scripts/verify_windows_hooks.ps1`，该脚本成功完成以下检查：

- 安装模块、测试应用和 instrumentation APK；检查模块说明界面的 UI XML。
- 模块启用期间，6 个应用 E2E 与 8 个 Ruby 渲染 E2E 全部通过。
- `RubyHookE2ETest` 的真实 Hook 测试通过。测试读取模块 ClassLoader 创建的实际 RubySpan，校验原文、注音范围、完整词语读音和显示读音。
- 日语、混合与长文本得到注音；富文本点击与文字选择保持可用；动态更新和 RecyclerView 复用得到当前文本的读音。
- 普通 CharSequence、Spannable、SpannedString 与已有 Spannable 缓冲的字符数组调用通过验证。PrecomputedText、输入、密码和超宽字号样例保持保护规则。
- 重新启动目标进程后，两个公开 TextView 方法完成一次注册。日志包含 `module_loaded`、`hook_registered`、`text_observed` 和 `ruby_applied`，没有失败事件。
- 日语样例报告四个注音范围；日语与混合样例含有 Han 候选，纯平假名与英文没有候选。敏感输入没有进入观测日志，宿主原文日志长度为零。

实际验证的样例 `今日は学校で日本語を勉強します。` 包含四个 Ruby 范围：

| UTF-16 半开区间 | 原文 | 平假名 |
| --- | --- | --- |
| `[0, 2)` | 今日 | きょう |
| `[3, 5)` | 学校 | がっこう |
| `[6, 9)` | 日本語 | にほんご |
| `[10, 12)` | 勉強 | べんきょう |

## 产物校验

下载归档的 SHA-256 与 GitHub Artifact API 返回值一致：`c94630c928eeca7a3657e989d290f42674ba41e9d2915e5a9fa6e8c275c5088b`。Windows 和本地标准输出目录中的 APK 校验值一致：

| APK | SHA-256 |
| --- | --- |
| `app-debug.apk` | `4f24c495f1c078216b6cb852a676cb6f1c71d33816b3c47d36766418ca53d869` |
| `testapp-debug.apk` | `3323e7fcf477c884286e6bd37397931f43fa044fec29cd54acad26f63f5e29b4` |
| `testapp-debug-androidTest.apk` | `a464568a69b0e318fd7f74f9896c2c73b8b803559f5328622526d1e99d9bc610` |

本地证据保存在被 Git 忽略的 `work/artifacts/run-37945088658/`、`work/ci-run37945088658/` 和 `work/windows-validation/run-37945088658/results/`。APK 保存在 README 指定的标准输出目录。

## 重复验证

保持专用虚拟设备运行，将三个云端 APK 放入 Windows 工作目录，并执行仓库脚本：

```powershell
pwsh -NoProfile -File scripts/provision_windows_apks.ps1
pwsh -NoProfile -File scripts/verify_windows_hooks.ps1
```

重新安装脚本仅允许使用 `FuriHook_API35`，只清除本项目三个测试包的数据，并恢复模块启用与默认作用域。验证脚本要求已安装 Vector API 102；任何检查失败都会终止。

Windows 工作目录 `results/` 保存 `instrumentation.txt`、`ruby-hook-instrumentation.txt`、`module-ui.xml`、`hook-framework-status.json`、`hook-scope.json`、`furihook-instrumentation-logcat.txt`、`furihook-logcat.txt`、`hook-events.json`、`hook-verification.json`、`windows-verification.json` 和 APK SHA-256。普通应用测试报告的 `hookExecutionChecked=false` 表示只记录应用测试；实际注入验证由 `hook-verification.json` 的 `hookExecutionVerified=true` 给出。

验证范围包含云端 API 28/36 的普通 Android 运行，以及 Windows API 35 的 Vector 注入。其他框架发行版、厂商 Android 和物理设备尚未执行验证。Compose、WebView、用户等级与样式配置当前保留独立接口，后续研发要求见 `PHASE_TWO.md`。

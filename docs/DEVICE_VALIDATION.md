# 设备验证

## 云端构建

[GitHub Actions 37905066843](https://github.com/kongjianguan/FuriHook/actions/runs/37905066843) 对应用代码提交 `8216a655b04a6c3e42211ca64fe34cf6ab9fe6ff` 执行验证：

- `:app:assembleDebug`、`:testapp:assembleDebug`、`:testapp:assembleDebugAndroidTest` 成功。
- 8 个核心单元测试通过，API 28 与 API 36 各 6 个 instrumentation E2E 通过。
- 两个 Android 模块的 Lint 没有错误。
- Modern API 入口、属性、作用域及 APK DEX 类定义检查通过。API 依赖没有打包进 APK。
- 两个云端模拟器成功安装并启动模块说明界面。

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

应用安装、模块界面检查与六个应用测试通过。真实日志包含 `module_loaded`、`hook_registered`、`text_observed`，并报告两个公开的 TextView 设置方法。日语与混合样例产生 Han 候选；纯平假名与英文样例没有候选。日志保留元数据，原文长度为零。

Windows 上使用的模块 APK SHA-256 为 `2b04f4ad97d0af10e7670c3489f9d5ec3ae991a3db89b688e81b1e69485c73fd`，与上述 Actions 的构建产物一致。验证依赖 Vector 的 API 102 实现；其他框架发行版及物理设备未执行验证。

## 重复验证

保持专用虚拟设备运行，将仓库的 PowerShell 脚本放入同一目录，再执行：

```powershell
pwsh -NoProfile -File scripts/verify_windows_hooks.ps1
```

脚本要求该设备已经安装 Vector、启用 FuriHook，并设置指定测试作用域。它执行真实 instrumentation，检查测试运行期间的 Hook 事件，再终止并启动测试应用，验证当前进程的初始化、重复注册控制、候选检测及敏感输入保护。任何检查失败都会终止。

工作目录 `results/` 保存 `instrumentation.txt`、`hook-framework-status.json`、`hook-scope.json`、`furihook-instrumentation-logcat.txt`、`furihook-logcat.txt`、`hook-events.json`、`hook-verification.json` 和 APK SHA-256。普通应用测试报告中的 `hookExecutionChecked=false` 表示该脚本只执行应用测试；Hook 验证结果由 `hook-verification.json` 给出。

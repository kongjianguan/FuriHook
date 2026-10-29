# WeChatMemoryGuard — Android 原生内存实验探针

> 实验分支。默认**不**执行任何内存回收。仅为 `com.tencent.mm` 主进程注册只读 Activity 生命周期探针。
> 本项目不记录聊天内容、图片内容、联系人、数据库数据或网络请求。

## 原理与验证边界

- **构建**：仅使用 GitHub Actions + Android SDK/NDK，产物为 ARM64 LSPosed APK，含 `libwmgpurge.so`。
- **注入**：兼容的 LSPosed Legacy API，`assets/xposed_init` 入口，代码严格检查包名与进程名都是 `com.tencent.mm`，绝不注入 `:push`、小程序及其他应用。
- **监控**：加载时通过 `Application.attach` 注册 ActivityLifecycleCallbacks；唯一的探针线程每 5 秒检查私有的手动请求文件。**被冻结时线程不会执行**，控制器在提交请求后调用一次系统 `am unfreeze`，不禁用系统冻结器。没有请求时不遍历对象、没有循环内存扫描。
- **手动 Purge**：Android Bionic `mallopt(M_PURGE, 0)`；仅尝试把已释放的分配器页归还内核，不调用 GC，不修改 Java 对象、Bitmap 或其他内存所有者。
- **防误触发**：需要包含当前 PID 的一次性请求；必须满足全部条件：主进程无 Started Activity ≥120 秒、外部提交前 OOM Adj ≥400 且当前前台不是微信、音频模式正常、当前 PID 尚未做过一次 Purge。OEM 系统在 `am unfreeze` 唤醒的瞬间可能暂时将 OOM Adj 设置为 0；仅对经过外部后台预检、显式标记为 `WAKE` 的请求，允许在没有 Started Activity 且后台已满 120 秒时通过该瞬时值，其余不低于 400 的条件不变。
- **结果**：记录到微信自身私有目录 `files/wechat-memory-guard/events.tsv`，仅包括时间、PID、事件名、PSS 前后差值和耗时，不记录应用数据。
- **保护**：如果检测失败则拒绝执行；在加载 JNI 失败时退化为只读监测；一个进程最多执行一次，不会定时自动回收。

## 安装（待 CI 通过与用户确认）

1. GitHub Actions 下载 `wechat-memory-guard-arm64-debug` Artifact，校验 SHA-256。
2. 在 Android 上安装实验 APK。
3. 如果 LSPosed 当前处于禁用状态，必须先从 KernelSU/模块管理器恢复启用，通常需要**重启**；本项目绝不自行修改 Root 模块状态或自动重启手机。
4. 在 LSPosed Manager 中启用本模块，作用域**只选微信**；这通常需要重启微信进程才能生效。微信登录/消息/支付/通话安全与兼容风险需自行评估。
5. 从 Termux 用 Root 检查日志：
   ```sh
   sh scripts/termux-control.sh status
   ```
6. **仅在确认微信不在通话、收付款、录音等活动时**，让微信在后台至少两分钟，再手动提交一次测试：
   ```sh
   sh scripts/termux-control.sh request
   # 或取消尚未处理的请求
   sh scripts/termux-control.sh cancel
   ```

请求若在前台、期限不足、OOM 优先级检查不通过或音频模式不合要求，立即拒绝并消费，不会潜伏在后台等待条件满足。`request` 文件使用微信 UID 和相同 SELinux MCS 标签，在原子重命名后立即临时解冻以便主进程消费请求；不会关闭系统缓存冻结器，也不会使用 sticky 解冻。

## 恢复

- 最简单：在 LSPosed Manager 中**取消本模块的微信作用域或直接禁用模块**，再重新启动微信进程。
- 若注入框架本身影响系统，可从 KernelSU 恢复禁用 LSPosed，但不要通过随意删除微信数据排错。
- 不会更改微信数据库、账号信息、密钥、APK 内容或自身网络权限。
- 如果微信卡住或异常，**停止测试**。不要在正在进行通话/收付款时测试。

## 验收指标

对比 **相同使用场景** 的后台 PSS、匿名 PSS、SwapPSS 和 Native Heap PSS，
并记录恢复前台时间、通知/消息、语音/图片/小程序的功能结果。
不能把文件映射 PSS、被换出的内存或 Native Heap Free 数字直接当成真实回收收益。

## 当前状态

版本 0.2.0-manual-wake：修复一代控制器写入 root-only request 和 Android 后台冻结导致的请求无法执行；仍需在相同 PID 上完成原生 Purge 的首次实机验证。此前日志中的 `deny_oom_adj_0` 是首次临时解冻后的安全拒绝，不代表 Purge 已执行。

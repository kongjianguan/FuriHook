# WeChat Forensics Probe (observe only)

Independent, permission-free LSPosed/Vector module for **com.tencent.mm 8.0.72 (versionCode 3100)**.

## Purpose

Observe *when* the app invokes verified diagnostics/reporting entrypoints:

- `com.tencent.mm.network.g1.C` — initiates Mars XLog upload (`Log.appenderFlush()` -> `IPxxLogic.uploadLog()`).
- `CrashUploadAlarmReceiver.onReceive` — triggers the native-crash upload workflow.
- `CrashUploaderService.i(Intent)` — crash uploader service.
- `com.tencent.mm.plugin.report.service.o0.run` — sends cLog stackreport over HTTPS.
- `JSAPIUploadLogHelperUI.onCreate` — web JSAPI log upload UI.
- `AccServiceInfoReporter.realReport` — accessibility service information reporting.
- `AccInterceptActionReporter.realReport` — accessibility action interception counts.

The probe **never inspects method arguments, messages, contacts, log contents, IDs, URLs, credentials, or network traffic**. It changes no behavior and makes no network requests. It emits one `WXForensicProbe EVENT <category> count=1` at first occurrence, then every 10th. No polling thread. Exceptions fail open to original app behavior. Every hook logs `HOOK_OK` or `HOOK_MISSING`.

Scope in Vector: **only `com.tencent.mm`, user 0**. Internally hooks only the main, `:push`, and `:tools` processes, excluding XWeb and mini-program processes from the first experiment.

## Safety and rollback

No cleartext XLog export. No upload request is triggered. The app's user storage is not modified. No launch Activity or permissions. Native `IPxxLogic.uploadLog` itself is intentionally not hooked. An observed EVENT means a Java entrypoint was called, not that the remote server received data.

To stop the probe, disable the module in Vector and reload affected WeChat processes. The existing independent memory optimization module is not modified. **Do not enable in versions other than pinned 8.0.72**; the probe refuses to install hooks on a version mismatch.

Build via GitHub Actions branch `experiment/wechat-forensics-probe`, artifact `wechat-forensics-probe-debug-apk`.


## Experimental 0.2.0: cheaper XLog severity at initialization

**Goal: reduce ordinary diagnostic logging overhead; this is not a chat-storage, database, account-security or risk-control bypass.** The module remains OFF by default, continues to observe the seven diagnostic lifecycle entries, and never reads log contents.

The WeChat 8.0.72/3100 APK's `com.tencent.mm.xlog.app.XLogSetup.keep_setupXLog()` invokes `com.tencent.mars.xlog.Xlog.appenderOpen(int level,int mode,String cache,String directory,String name,int days)`. The optional hook intercepts this one-time initialization method, raises the first integer argument to `4` (Mars `LEVEL_ERROR`) only when it is less than 4, and does not intercept `logWrite` or any hot-path Java log method. It preserves ERROR/FATAL and changes neither network/CGI response nor crash handling. This may leave other logging providers, appbrand native processes and already opened appenders untouched.

To opt in on an explicitly authorized test device after establishing a baseline:

```sh
su -c 'settings put global wfp_xlog_error_only 1'
# Reload WeChat processes under a bounded and reversible pilot; simply setting the flag
# does not affect native XLog instances already initialized.
```

Disable **for the next process launch**: `su -c 'settings delete global wfp_xlog_error_only'`, followed by a controlled WeChat reload. If no `EVENT xlog.appender.open` and `XLOG_LEVEL_OVERRIDE` appears after restart, do NOT claim active suppression. Do not reinstall or tamper with log files. Verify connection/notifications, process survival, crash count, `/proc/<pid>/io`, XLog increments and repeated native initialization. Preserve all normal business data and never block security/anti-abuse telemetry as part of this logging experiment.

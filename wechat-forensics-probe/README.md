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

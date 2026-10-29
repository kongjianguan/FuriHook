# ColorOS Telemetry Probe · 0.1.0 observe-only

A standalone, read-only LSPosed module for the OnePlus Ace 5 Ultra / ColorOS research environment.

- Target scopes: **com.oplus.olc** and **com.oplus.framework.network.drs** (main processes only).
- No permission requests, no network access, no user data, no method result or argument mutations.
- Hooks OLC DCS / Retry / CrashBox and DRS upload `onStartJob`, and observes JobScheduler.schedule for upload-related job classes.
- Captures only aggregate event counts, a single first-occurrence event, process name and PID in Android logcat under tag **COTProbe**.
- Does not inspect payloads, log files, authentication tokens, network addresses or device identifiers.
- SystemUI and system_server explicitly excluded.
- Threads wake once per minute for counters (process freezer can delay flushes).
- Any hook failure falls back to logging an error marker and lets the target process continue.

## Workflow

Build with GitHub Actions (branch experiment/coloros-telemetry-probe).
Install APK, select **only OLC and DRS** in LSPosed, reload target processes one at a time if safe (system UID components should not be force-stopped indiscriminately).
Read results with root:

```sh
su -c '/system/bin/logcat -d -s COTProbe:I "*:S" | tail -n 100'
```

Compare CPU, PSS, error frequency, socket counts, JobScheduler pending history with baseline, before attempting any behavior-changing hook. In this version there is **no toggle to block telemetry** because it cannot block anything.

Rollback: disable this module in LSPosed and reload affected processes; if framework fails, disable module from KernelSU. Existing WeChatMemoryGuard is independent.

Guardrail: no changes to OLC or DRS packages, no cancelling queued jobs, no firewall changes, no revocation of permissions. Future narrow blocking should be introduced one edge at a time only after observed call patterns justify it.


## Version 0.2.0: exact-service suppression pilot (default OFF)

This version remains observe-only until *all* targeted flags are explicitly set to 1 by a root administrator and the affected process is reloaded. Independent switches:

- `settings put global ctp_block_drs_upload 1`: suppress only `com.oplus.drs.core.schduler.UploadJobService`.
- `settings put global ctp_block_olc_dcs 1`: suppress only OLC `UploadJobService` and `RetryLogUploadJobService`.
- `settings put global ctp_block_olc_crashbox 1`: suppress only OLC `CrashBoxJobService` (not part of the first pilot).

For the exact matching `JobInfo.getService().getClassName()`, `JobScheduler.schedule` returns `RESULT_SUCCESS` without queueing the job. For already scheduled matching `JobService.onStartJob`, the hook returns `false` immediately without invoking uploader code. All other jobs, services, receivers and Binder interfaces are unchanged.

**Does not guarantee zero telemetry:** direct HTTP uploads, events sent via provider/Binder, other OEM components and previously running workers remain outside this interception.

Reversion: `settings delete global ctp_block_drs_upload` (and the corresponding other flags), disable the module, reload the target process. The flags are read only at process start and default to OFF if unavailable.

Before testing on production, run each flag for a bounded period with an external watchdog that restores the flag and restarts the corresponding process on errors. Monitor for retry storms and core-process restarts. Do not enable all flags at once.


## Version 0.3: stop Metis clipboard telemetry at its production source

Static analysis of ColorOS Metis `com.oplus.metis` found:
`UserDataCollectionUtilManager.onClipboardTextReport(Context, clipboardText, ...)` →
`toBase64Twice` → static-key `AES128EncryptionHelper.encrypt` →
`onCommonWithClipboard` → `OplusTrack.onCommon` → DRS.
The local DRS database contained 193 decryptable clipboard events; 193 were fully decoded locally to content, including 84 URL-prefix and 78 non-ASCII samples. **No clipboard plaintext, AES key, tokens, account identifiers or payload values were printed or stored outside the original database.**

The exact Metis reporter (a void method) has a standalone opt-in, default **OFF**:
`settings put global ctp_block_metis_clipboard 1`; reload `com.oplus.metis`.
When active, its method body is skipped and returns void; clipboard APIs, copying and pasting are not altered. No clipboard contents are inspected or logged by the hook.

Rollback: `settings delete global ctp_block_metis_clipboard`, then restart Metis; or disable the LSPosed module and restart Metis. Only scope Metis main process, never Android System, system_server, SystemUI or clipboard core.

This only blocks that one reporting function; it does not guarantee other Metis or OS clipboard telemetry paths are absent. Preserve any existing records until separately authorized for deletion.

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

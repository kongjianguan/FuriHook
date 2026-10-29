package dev.kongjianguan.colorostelemetryprobe;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Strictly read-only telemetry lifecycle probe.
 * No method interception changes results, args, app data, network traffic or scheduling.
 * Scope: OLC and DRS main processes only. Never system_server or SystemUI.
 */
public final class ProbeEntry implements IXposedHookLoadPackage {
    private static final String TAG = "COTProbe";
    private static final String OLC = "com.oplus.olc";
    private static final String DRS = "com.oplus.framework.network.drs";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> FIRST = new ConcurrentHashMap<>();
    private static volatile boolean BLOCK_DRS;
    private static volatile boolean BLOCK_OLC_DCS;
    private static volatile boolean BLOCK_OLC_CRASHBOX;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!(OLC.equals(load.packageName) && OLC.equals(load.processName))
                && !(DRS.equals(load.packageName) && DRS.equals(load.processName))) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!INSTALLED.compareAndSet(false, true)) return;
                            try {
                                Application app = (Application) param.thisObject;
                                attachProbe(app, load.processName);
                            } catch (Throwable e) {
                                safeLog("attach_failed " + e.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable e) {
            safeLog("entry_failed " + e.getClass().getSimpleName());
        }
    }

    private static void attachProbe(Application app, String processName) {
        // Read only a purpose-specific configuration flag. Unknown or malformed => observe-only.
        try {
            BLOCK_DRS = DRS.equals(processName) &&
                    Settings.Global.getInt(app.getContentResolver(), "ctp_block_drs_upload", 0) == 1;
            BLOCK_OLC_DCS = OLC.equals(processName) &&
                    Settings.Global.getInt(app.getContentResolver(), "ctp_block_olc_dcs", 0) == 1;
            BLOCK_OLC_CRASHBOX = OLC.equals(processName) &&
                    Settings.Global.getInt(app.getContentResolver(), "ctp_block_olc_crashbox", 0) == 1;
        } catch (Throwable e) {
            BLOCK_DRS = BLOCK_OLC_DCS = BLOCK_OLC_CRASHBOX = false;
            safeLog("settings_unavailable " + e.getClass().getSimpleName());
        }
        safeLog("READY process=" + processName + " pid=" + Process.myPid() +
                " drs_block=" + BLOCK_DRS +
                " olc_dcs_block=" + BLOCK_OLC_DCS +
                " olc_crashbox_block=" + BLOCK_OLC_CRASHBOX);
        final ClassLoader loader = app.getClassLoader();
        if (OLC.equals(processName)) {
            hookJob(loader, "com.oplus.olc.dcs.service.UploadJobService", "olc.dcs");
            hookJob(loader, "com.oplus.olc.dcs.upload.RetryLogUploadJobService", "olc.retry");
            hookJob(loader, "com.oplus.olc.crashbox.CrashBoxJobService", "olc.crashbox");
            hookService(loader, "com.oplus.olc.coreservice.LogCoreService", "olc.logcore");
            hookService(loader, "com.oplus.olc.service.OlcService", "olc.binder");
        }
        if (DRS.equals(processName)) {
            hookJob(loader, "com.oplus.drs.core.schduler.UploadJobService", "drs.upload");
            hookService(loader, "com.oplus.drs.core.component.DrsChannelService", "drs.channel");
        }
        try {
            JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) {
                XposedHelpers.findAndHookMethod(scheduler.getClass(), "schedule", JobInfo.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    JobInfo info = (JobInfo) p.args[0];
                                    if (info == null || info.getService() == null) return;
                                    String cls = info.getService().getClassName();
                                    if (cls == null) return;
                                    // Count only upload-related scheduling inside this process.
                                    if (cls.contains("Upload") || cls.contains("CrashBox")
                                            || cls.contains("Report") || cls.contains("Track")) {
                                        hit("job.schedule." + safeCategory(cls));
                                    }
                                    if (shouldBlockClass(cls)) {
                                        hit("job.schedule.suppressed." + safeCategory(cls));
                                        // Pretend scheduling succeeded, instead of causing caller retries.
                                        p.setResult(JobScheduler.RESULT_SUCCESS);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                safeLog("HOOK_OK JobScheduler.schedule");
            }
        } catch (Throwable e) {
            safeLog("HOOK_MISSING JobScheduler.schedule " + e.getClass().getSimpleName());
        }
        Thread reporter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(60_000L); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                for (String k : COUNTERS.keySet()) {
                    AtomicLong count = COUNTERS.get(k);
                    if (count == null) continue;
                    long n = count.getAndSet(0);
                    if (n != 0) safeLog("COUNT " + k + " n=" + n);
                }
            }
        }, "COTProbe-Counter");
        reporter.setDaemon(true);
        reporter.start();
    }

    // Strict class equality: never suppress a general system service or another app's job.
    private static boolean shouldBlockClass(String cls) {
        if (BLOCK_DRS && "com.oplus.drs.core.schduler.UploadJobService".equals(cls)) return true;
        if (BLOCK_OLC_DCS && ("com.oplus.olc.dcs.service.UploadJobService".equals(cls)
                || "com.oplus.olc.dcs.upload.RetryLogUploadJobService".equals(cls))) return true;
        return BLOCK_OLC_CRASHBOX && "com.oplus.olc.crashbox.CrashBoxJobService".equals(cls);
    }

    private static boolean shouldBlockLabel(String label) {
        if (BLOCK_DRS && "drs.upload".equals(label)) return true;
        if (BLOCK_OLC_DCS && ("olc.dcs".equals(label) || "olc.retry".equals(label))) return true;
        return BLOCK_OLC_CRASHBOX && "olc.crashbox".equals(label);
    }

    private static String safeCategory(String className) {
        if (className.contains("CrashBox")) return "crashbox";
        if (className.contains("Retry")) return "retry";
        if (className.contains("Dcs") || className.contains("dcs")) return "dcs";
        if (className.contains("Upload")) return "upload";
        if (className.contains("Report")) return "report";
        if (className.contains("Track")) return "track";
        return "other";
    }

    private static void hookJob(ClassLoader loader, String name, String label) {
        try {
            XposedHelpers.findAndHookMethod(name, loader, "onStartJob", JobParameters.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (shouldBlockLabel(label)) {
                                hit(label + ".suppressed_start");
                                // Returning false ends JobService synchronously; no upload executes.
                                p.setResult(false);
                            } else {
                                hit(label + ".start");
                            }
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                // Only the boolean return status, never inspect Job extras.
                                if (!shouldBlockLabel(label) && Boolean.FALSE.equals(p.getResult())) {
                                    hit(label + ".returned_false");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            safeLog("HOOK_OK " + label + ".start");
        } catch (Throwable e) {
            safeLog("HOOK_MISSING " + label + ".start " + e.getClass().getSimpleName());
        }
    }

    private static void hookService(ClassLoader loader, String name, String label) {
        try {
            XposedHelpers.findAndHookMethod(name, loader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) { hit(label + ".created"); }
            });
            safeLog("HOOK_OK " + label + ".created");
        } catch (Throwable e) {
            safeLog("HOOK_MISSING " + label + ".created " + e.getClass().getSimpleName());
        }
    }

    private static void hit(String name) {
        try {
            COUNTERS.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
            AtomicBoolean first = FIRST.computeIfAbsent(name, k -> new AtomicBoolean());
            if (first.compareAndSet(false, true)) safeLog("FIRST " + name);
        } catch (Throwable ignored) {}
    }

    private static void safeLog(String text) {
        try { Log.i(TAG, text); } catch (Throwable ignored) {}
    }
}

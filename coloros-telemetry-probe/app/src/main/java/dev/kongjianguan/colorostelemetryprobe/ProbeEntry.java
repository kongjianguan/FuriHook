package dev.kongjianguan.colorostelemetryprobe;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Process;
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
        safeLog("READY process=" + processName + " pid=" + Process.myPid() + " mode=observe_only");
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
                        protected void beforeHookedMethod(MethodHookParam p) { hit(label + ".start"); }
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                // Only the boolean return status, never inspect Job extras.
                                if (Boolean.FALSE.equals(p.getResult())) hit(label + ".returned_false");
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

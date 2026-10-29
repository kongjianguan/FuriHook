package dev.kongjianguan.wechatforensicsprobe;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WeChat 8.0.72 diagnostics-path observer with an OFF-by-default
 * one-time optional XLog verbosity reduction (no per-log Hook).
 *
 * Not a clipboard, chat, contacts, network or file inspection tool.
 * Existing diagnostic hooks only count events, never inspect private data.
 * If explicitly enabled, appenderOpen's integer severity is raised to ERROR.
 * No chat, contacts, file, network payload, stack dump or account data is collected.
 */
public final class ProbeEntry implements IXposedHookLoadPackage {
    private static final String TAG = "WXForensicProbe";
    private static final String TARGET = "com.tencent.mm";
    private static final long PINNED_VERSION_CODE = 3100L;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean EARLY_XLOG_INSTALLED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    private static volatile boolean XLOG_ERROR_ONLY;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!TARGET.equals(load.packageName)) return;
        final String processName = load.processName;
        if (!(TARGET.equals(processName) || (TARGET + ":push").equals(processName)
                || (TARGET + ":tools").equals(processName))) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // WeChat may initialize XLog during attachBaseContext().
                            // Install the one-time level hook BEFORE Application.attach().
                            if (!EARLY_XLOG_INSTALLED.compareAndSet(false, true)) return;
                            try {
                                Context base = (Context) param.args[0];
                                long version = base.getPackageManager()
                                        .getPackageInfo(TARGET, 0).getLongVersionCode();
                                if (version != PINNED_VERSION_CODE) {
                                    safeLog("XLOG_VERSION_MISMATCH");
                                    return;
                                }
                                try {
                                    XLOG_ERROR_ONLY = Settings.Global.getInt(
                                            base.getContentResolver(), "wfp_xlog_error_only", 0) == 1;
                                } catch (Throwable configError) {
                                    XLOG_ERROR_ONLY = false;
                                    safeLog("XLOG_FLAG_UNAVAILABLE");
                                }
                                hookXlogConfig(base.getClassLoader());
                                safeLog("XLOG_EARLY_READY enabled=" + XLOG_ERROR_ONLY);
                            } catch (Throwable e) {
                                safeLog("XLOG_EARLY_INIT_FAILED "
                                        + e.getClass().getSimpleName());
                            }
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!INSTALLED.compareAndSet(false, true)) return;
                            try {
                                Application app = (Application) param.thisObject;
                                long version = app.getPackageManager()
                                        .getPackageInfo(TARGET, 0).getLongVersionCode();
                                if (version != PINNED_VERSION_CODE) {
                                    safeLog("VERSION_MISMATCH versionCode=" + version
                                            + " expected=" + PINNED_VERSION_CODE);
                                    return;
                                }
                                try {
                                    XLOG_ERROR_ONLY = Settings.Global.getInt(
                                            app.getContentResolver(), "wfp_xlog_error_only", 0) == 1;
                                } catch (Throwable configError) {
                                    XLOG_ERROR_ONLY = false; // Fail open, keep observation working.
                                    safeLog("XLOG_FLAG_UNAVAILABLE");
                                }
                                install(app.getClassLoader(), processName);
                            } catch (Throwable error) {
                                safeLog("ATTACH_FAILED " + error.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable error) {
            safeLog("ENTRY_FAILED " + error.getClass().getSimpleName());
        }
    }

    private static void install(ClassLoader loader, String processName) {
        safeLog("READY pid=" + Process.myPid() + " process=" + processName
                + " xlog_error_only=" + XLOG_ERROR_ONLY);

        // Native Mars IPxxLogic.uploadLog is deliberately NOT hooked:
        // the method is native and may run on latency-sensitive paths.
        hook(loader, "com.tencent.mm.network.g1", "C",
                "xlog.upload.request",
                int[].class, boolean.class, long.class, int.class, int.class,
                String.class, String.class, String.class);

        hook(loader, "com.tencent.mm.sandbox.monitor.CrashUploadAlarmReceiver",
                "onReceive", "crash.alarm",
                Context.class, Intent.class);

        hook(loader, "com.tencent.mm.crash.CrashUploaderService", "i",
                "crash.upload.service", Intent.class);

        hook(loader, "com.tencent.mm.plugin.report.service.o0",
                "run", "clog.stackreport.run");

        hook(loader, "com.tencent.mm.ui.JSAPIUploadLogHelperUI",
                "onCreate", "xlog.jsapi.upload.ui", Bundle.class);

        hook(loader, "com.tencent.mm.accessibility.core.AccServiceInfoReporter",
                "realReport", "accessibility.service.info",
                findClass(loader, "com.tencent.mm.accessibility.core.ServiceReportInfo"));

        hook(loader, "com.tencent.mm.accessibility.core.AccInterceptActionReporter",
                "realReport", "accessibility.intercept.count");
    }

    /**
     * Intercepts only initialization, NOT logWrite or any per-event hot path.
     * The Tencent Mars appender signature is pinned to WeChat 8.0.72/3100.
     * Severity ERROR (4) retains ERROR and FATAL records, but reduces
     * verbose, debug, info and warning log writes.
     */
    private static void hookXlogConfig(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mars.xlog.Xlog", loader,
                    "appenderOpen", int.class, int.class, String.class,
                    String.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            hit("xlog.appender.open");
                            if (!XLOG_ERROR_ONLY) return;
                            try {
                                int originalLevel = (Integer) param.args[0];
                                if (originalLevel < 4) {
                                    param.args[0] = 4;
                                    safeLog("XLOG_LEVEL_OVERRIDE original=" + originalLevel
                                            + " new=4 pid=" + Process.myPid());
                                } else {
                                    safeLog("XLOG_LEVEL_PRESERVED original=" + originalLevel);
                                }
                            } catch (Throwable ignored) {
                                safeLog("XLOG_LEVEL_OVERRIDE_FAILED");
                            }
                        }
                    });
            safeLog("HOOK_OK xlog.appender.open");
        } catch (Throwable e) {
            safeLog("HOOK_MISSING xlog.appender.open "
                    + e.getClass().getSimpleName());
        }
    }

    private static Class<?> findClass(ClassLoader loader, String name) {
        try {
            return XposedHelpers.findClass(name, loader);
        } catch (Throwable error) {
            safeLog("CLASS_MISSING accessibility.ServiceReportInfo");
            return null;
        }
    }

    private static void hook(ClassLoader loader, String className, String methodName,
                             String label, Object... signature) {
        try {
            for (Object item : signature) {
                if (item == null) {
                    safeLog("HOOK_MISSING " + label + " missing_signature_class");
                    return;
                }
            }
            Object[] arguments = Arrays.copyOf(signature, signature.length + 1);
            arguments[signature.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // Strictly never inspect param.args, param.thisObject, return,
                    // messages, contacts, event payloads, usernames or file contents.
                    hit(label);
                }
            };
            XposedHelpers.findAndHookMethod(className, loader, methodName, arguments);
            safeLog("HOOK_OK " + label);
        } catch (Throwable error) {
            safeLog("HOOK_MISSING " + label + " " + error.getClass().getSimpleName());
        }
    }

    private static void hit(String label) {
        try {
            long n = COUNTS.computeIfAbsent(label, key -> new AtomicLong())
                    .incrementAndGet();
            // First observation, then only each 10th; no background reporting thread.
            if (n == 1 || n % 10 == 0) {
                safeLog("EVENT " + label + " count=" + n + " pid=" + Process.myPid());
            }
        } catch (Throwable ignored) {
            // Never cause the target application to throw because of our probe.
        }
    }

    private static void safeLog(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }
}

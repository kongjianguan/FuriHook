package dev.kongjianguan.wechatforensicsprobe;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
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
 * Strictly read-only WeChat 8.0.72 diagnostics-path event counter.
 *
 * Not a clipboard, chat, contacts, network or file inspection tool.
 * All hooks are before-callbacks that NEVER modify a method argument or return.
 * Logging is aggregate label + count only, without argument inspection.
 */
public final class ProbeEntry implements IXposedHookLoadPackage {
    private static final String TAG = "WXForensicProbe";
    private static final String TARGET = "com.tencent.mm";
    private static final long PINNED_VERSION_CODE = 3100L;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

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
                + " mode=observe_only");

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

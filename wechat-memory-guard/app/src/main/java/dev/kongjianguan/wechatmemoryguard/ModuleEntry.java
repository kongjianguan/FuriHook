package dev.kongjianguan.wechatmemoryguard;

import android.app.Activity;
import android.app.Application;
import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// Experimental: one process, one manual purge maximum; no automatic memory reclamation.
// Never hook message, network, database, contacts, media contents, or authentication code.
public final class ModuleEntry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.tencent.mm";
    private static final String MODULE = "dev.kongjianguan.wechatmemoryguard";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!TARGET.equals(load.packageName) || !TARGET.equals(load.processName)) {
            return;
        }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!INSTALLED.compareAndSet(false, true)) {
                            return;
                        }
                        try {
                            Application app = (Application) param.thisObject;
                            new Probe(app).start();
                        } catch (Throwable error) {
                            XposedBridge.log("WMG probe initialization failed: " +
                                    error.getClass().getSimpleName());
                        }
                    }
                });
    }

    private static final class Probe implements Application.ActivityLifecycleCallbacks {
        private static final long BACKGROUND_MIN_MS = 120_000L;
        private static final long POLL_INTERVAL_MS = 5_000L;
        private final Application app;
        private final File dir;
        private final File requestFile;
        private final File recordFile;
        private final AtomicInteger activeActivities = new AtomicInteger(0);
        private final AtomicBoolean purged = new AtomicBoolean(false);
        private volatile long backgroundSince = SystemClock.elapsedRealtime();
        private volatile boolean nativeAvailable;

        Probe(Application app) {
            this.app = app;
            dir = new File(app.getFilesDir(), "wechat-memory-guard");
            requestFile = new File(dir, "request");
            recordFile = new File(dir, "events.tsv");
        }

        void start() {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            app.registerActivityLifecycleCallbacks(this);
            try {
                // Resolve the JNI library through the LSPosed module class loader.
                // Do not query the module package from inside WeChat: Android package
                // visibility restrictions can raise NameNotFoundException.
                System.loadLibrary("wmgpurge");
                nativeAvailable = true;
                record("ready_native", 0, 0);
            } catch (Throwable error) {
                record("ready_observe_only_" + error.getClass().getSimpleName(), 0, 0);
            }
            Thread worker = new Thread(this::poll, "WMG-ManualProbe");
            worker.setDaemon(true);
            worker.start();
        }

        private void poll() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (requestFile.isFile()) {
                        // Consume exactly once. A stale trigger must never execute later.
                        String request;
                        try (BufferedReader reader = new BufferedReader(new FileReader(requestFile))) {
                            request = reader.readLine();
                        }
                        if (!requestFile.delete()) {
                            record("deny_request_not_consumed", 0, 0);
                        } else {
                            evaluate(request);
                        }
                    }
                } catch (Throwable error) {
                    record("probe_error_" + error.getClass().getSimpleName(), 0, 0);
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void evaluate(String command) {
            int pid = Process.myPid();
            if (!("PURGE:" + pid).equals(command)) {
                record("deny_invalid_pid_or_command", 0, 0);
                return;
            }
            if (!nativeAvailable) {
                record("deny_native_unavailable", 0, 0);
                return;
            }
            if (purged.get()) {
                record("deny_once_per_process", 0, 0);
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - backgroundSince;
            if (activeActivities.get() != 0 || elapsed < BACKGROUND_MIN_MS) {
                record("deny_visible_or_recent", 0, elapsed);
                return;
            }
            int adj = oomAdj();
            // Require clear Android background/cache priority; never touch a foreground call.
            if (adj < 400) {
                record("deny_oom_adj_" + adj, 0, elapsed);
                return;
            }
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL) {
                record("deny_audio_active", 0, elapsed);
                return;
            }
            if (!purged.compareAndSet(false, true)) {
                record("deny_race", 0, elapsed);
                return;
            }
            long before = pssKiB();
            long start = SystemClock.elapsedRealtime();
            int result = NativeBridge.purge(); // Bionic mallopt(M_PURGE,0), not M_PURGE_ALL.
            long took = SystemClock.elapsedRealtime() - start;
            long after = pssKiB();
            record(result == 1 ? "purge_ok" : "purge_return_0", before - after, took);
        }

        private int oomAdj() {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/oom_score_adj"))) {
                return Integer.parseInt(reader.readLine().trim());
            } catch (Throwable error) {
                return -1; // Fail closed: cannot prove background priority.
            }
        }

        private long pssKiB() {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/smaps_rollup"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Pss:")) {
                        return Long.parseLong(line.substring(4).trim().split("\\s+")[0]);
                    }
                }
            } catch (Throwable ignored) {
                // Numeric metadata only; no stack traces or personal data.
            }
            return -1;
        }

        private synchronized void record(String event, long pssDeltaKiB, long durationMs) {
            // Local to WeChat's private app directory; root can inspect it.
            try (FileWriter writer = new FileWriter(recordFile, true)) {
                writer.write(System.currentTimeMillis() + "\t" + Process.myPid() + "\t" +
                        event + "\t" + pssDeltaKiB + "\t" + durationMs + "\n");
            } catch (IOException ignored) {
                // Never crash the host app for telemetry.
            }
        }

        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {
            activeActivities.incrementAndGet();
            backgroundSince = Long.MAX_VALUE;
        }
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {
            if (activeActivities.decrementAndGet() <= 0) {
                activeActivities.set(0);
                backgroundSince = SystemClock.elapsedRealtime();
            }
        }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }
}

package dev.furihook.hook;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.JsonWriter;
import android.widget.TextView;

import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.furihook.BuildConfig;
import dev.furihook.config.DetectionConfig;
import dev.furihook.engine.JapaneseDetector;

final class TextViewObserver {
    private final ModuleLog log;
    private final String packageName;
    private final String processName;
    private final WeakHashMap<TextView, ViewState> states = new WeakHashMap<>();
    private final WindowRateLimiter captures = new WindowRateLimiter(DetectionConfig.CAPTURES_PER_SECOND);
    private final WindowRateLimiter logs = new WindowRateLimiter(DetectionConfig.LOGS_PER_SECOND);
    private final AtomicLong skipped = new AtomicLong();
    private final ThreadPoolExecutor worker;

    TextViewObserver(ModuleLog log, String packageName, String processName) {
        this.log = log;
        this.packageName = packageName;
        this.processName = processName;
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DetectionConfig.QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "FuriHook-detection");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }

    void observe(TextView view) {
        if (!BuildConfig.DETECTION_LOGS || SensitiveTextPolicy.shouldSkip(view)) {
            return;
        }
        CharSequence text = view.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (states) {
            ViewState old = states.get(view);
            if (old != null && (now - old.timeMs < DetectionConfig.VIEW_INTERVAL_MS
                    || text instanceof String && old.text.get() == text)) {
                skipped.incrementAndGet();
                return;
            }
            if (!captures.acquire(now)) {
                skipped.incrementAndGet();
                return;
            }
            if (states.size() >= DetectionConfig.MAX_TRACKED_VIEWS) {
                states.clear();
            }
            states.put(view, new ViewState(now, text));
        }

        int length = text.length();
        int end = Math.min(length, DetectionConfig.MAX_SNAPSHOT_UTF16);
        if (end < length && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        // 仅复制有界字符快照；后台任务不持有 View、Context、Chain 或原始 Spannable。
        String snapshot = TextUtils.substring(text, 0, end);
        String viewClass = view.getClass().getName();
        int viewId = view.getId();
        try {
            worker.execute(() -> detect(snapshot, length, viewClass, viewId));
        } catch (RejectedExecutionException rejected) {
            skipped.incrementAndGet();
        }
    }

    void failure(Throwable failure) {
        if (logs.acquire(SystemClock.elapsedRealtime())) {
            log.failure("observation_failed", failure);
        }
    }

    private void detect(String snapshot, int originalLength, String viewClass, int viewId) {
        try {
            boolean candidate = JapaneseDetector.containsKanjiCandidate(snapshot);
            if (!logs.acquire(SystemClock.elapsedRealtime())) {
                skipped.incrementAndGet();
                return;
            }
            StringWriter output = new StringWriter();
            try (JsonWriter json = new JsonWriter(output)) {
                json.beginObject();
                json.name("event").value("text_observed");
                json.name("package").value(packageName);
                json.name("process").value(processName);
                json.name("viewClass").value(viewClass);
                json.name("viewId").value(viewId);
                json.name("utf16Length").value(originalLength);
                json.name("sampleUtf16Length").value(snapshot.length());
                json.name("sampleCodePoints").value(snapshot.codePointCount(0, snapshot.length()));
                json.name("containsKanjiCandidate").value(candidate);
                json.name("language").value("undetermined");
                json.name("truncated").value(originalLength != snapshot.length());
                json.name("omittedSinceLastLog").value(skipped.getAndSet(0));
                json.name("textExcerptLength").value(0);
                json.endObject();
            }
            log.event(output.toString());
        } catch (Throwable failure) {
            failure(failure);
        }
    }

    private static final class ViewState {
        final long timeMs;
        final WeakReference<CharSequence> text;

        ViewState(long timeMs, CharSequence text) {
            this.timeMs = timeMs;
            this.text = new WeakReference<>(text);
        }
    }
}

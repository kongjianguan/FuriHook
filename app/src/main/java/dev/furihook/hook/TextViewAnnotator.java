package dev.furihook.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.PrecomputedText;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.util.JsonWriter;
import android.view.View;
import android.widget.TextView;

import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.furihook.BuildConfig;
import dev.furihook.config.DetectionConfig;
import dev.furihook.engine.JapaneseDetector;
import dev.furihook.engine.ReadingEngine;
import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubySpan;
import dev.furihook.renderer.RubyStyle;
import dev.furihook.renderer.RubyTextRenderer;

final class TextViewAnnotator {
    private static final RubyStyle STYLE = new RubyStyle() {
        @Override public float getTextSizeScale() { return 0.5f; }
        @Override public float getVerticalOffsetEm() { return 0f; }
        @Override public float getInterlinearSpacingEm() { return 0.08f; }
    };

    private final ModuleLog log;
    private final String packageName;
    private final String processName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<TextView, ViewState> states = new WeakHashMap<>();
    private final LinkedHashSet<ViewState> pending = new LinkedHashSet<>();
    private final WindowRateLimiter logs = new WindowRateLimiter(DetectionConfig.LOGS_PER_SECOND);
    private final WindowRateLimiter applicationLogs = new WindowRateLimiter(DetectionConfig.LOGS_PER_SECOND);
    private final AtomicLong skipped = new AtomicLong();
    private final RubyTextRenderer renderer = new RubyTextRenderer();
    private final ReadingEngine engine;
    private final LinkedHashMap<String, List<RubySegment>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ThreadPoolExecutor worker;
    private boolean draining;
    private boolean analysisDisabled;

    TextViewAnnotator(ModuleLog log, String packageName, String processName, ReadingEngine engine) {
        this.log = log;
        this.packageName = packageName;
        this.processName = processName;
        this.engine = engine;
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "FuriHook-reading");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }

    boolean shouldPrepareBuffer(TextView view, CharSequence text, TextView.BufferType type) {
        return Looper.myLooper() == Looper.getMainLooper()
                && type != null && type != TextView.BufferType.EDITABLE
                && type != TextView.BufferType.SPANNABLE
                && text != null && text.length() > 0
                && !(text instanceof Editable) && !(text instanceof PrecomputedText)
                && !SensitiveTextPolicy.shouldSkip(view)
                && view.getTransformationMethod() == null && !renderer.hasRuby(text)
                && containsCandidateWithinSnapshot(text);
    }

    void observe(TextView view) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return;
        }
        CharSequence text = view.getText();
        ViewState state;
        synchronized (states) {
            state = states.get(view);
            if (state == null && states.size() >= DetectionConfig.MAX_TRACKED_VIEWS
                    || pending.size() >= DetectionConfig.MAX_TRACKED_VIEWS
                    && !pending.contains(state)) {
                for (Map.Entry<TextView, ViewState> entry : states.entrySet()) {
                    removeLayoutListener(entry.getKey(), entry.getValue());
                }
                states.clear();
                pending.clear();
                state = null;
            }
            if (state == null) {
                state = new ViewState(view);
                states.put(view, state);
            }
            state.generation++;
            state.input = null;
            state.result = null;
            removeLayoutListener(view, state);
            if (protectedText(view) || text == null || text.length() == 0 || renderer.hasRuby(text)) {
                pending.remove(state);
                return;
            }
            int end = snapshotEnd(text);
            String snapshot = TextUtils.substring(text, 0, end);
            state.input = new Input(snapshot, text, state.generation, view.getId(),
                    view.getClass().getName());
            if (!pending.add(state)) {
                skipped.incrementAndGet();
            }
            if (!draining) {
                draining = true;
                worker.execute(this::drain);
            }
        }
    }

    void failure(String event, Throwable failure) {
        if (logs.acquire(SystemClock.elapsedRealtime())) {
            log.failure(event, failure);
        }
    }

    private static boolean protectedText(TextView view) {
        return SensitiveTextPolicy.shouldSkip(view) || view.getTransformationMethod() != null
                || view.getText() instanceof PrecomputedText;
    }

    private static int snapshotEnd(CharSequence text) {
        int end = Math.min(text.length(), DetectionConfig.MAX_SNAPSHOT_UTF16);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return end;
    }

    private static boolean containsCandidateWithinSnapshot(CharSequence text) {
        int end = snapshotEnd(text);
        for (int index = 0; index < end;) {
            int codePoint = Character.codePointAt(text, index);
            if (JapaneseDetector.isCjkIdeographCandidate(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private void drain() {
        while (true) {
            ViewState state;
            Input input;
            synchronized (states) {
                if (pending.isEmpty()) {
                    draining = false;
                    return;
                }
                state = pending.iterator().next();
                pending.remove(state);
                TextView view = state.view.get();
                input = view != null && states.get(view) == state ? state.input : null;
            }
            if (input != null) {
                analyze(state, input);
            }
        }
    }

    private void analyze(ViewState state, Input input) {
        try {
            long started = SystemClock.elapsedRealtime();
            boolean candidate = JapaneseDetector.containsKanjiCandidate(input.snapshot);
            logObservation(input, candidate);
            if (!candidate || !input.spannable || analysisDisabled) {
                return;
            }
            List<RubySegment> segments = cache.get(input.snapshot);
            if (segments == null) {
                segments = Collections.unmodifiableList(new ArrayList<>(engine.analyze(input.snapshot)));
                cache.put(input.snapshot, segments);
                if (cache.size() > DetectionConfig.CACHE_CAPACITY) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
            ApplyResult result = new ApplyResult(input, segments,
                    SystemClock.elapsedRealtime() - started);
            main.post(() -> receive(state, result));
        } catch (Throwable failure) {
            analysisDisabled = true;
            failure("ruby_analysis_failed", failure);
        }
    }

    private void receive(ViewState state, ApplyResult result) {
        try {
            TextView view = currentView(state, result.input);
            if (view == null) {
                skipped.incrementAndGet();
                return;
            }
            state.result = result;
            if (state.layoutListener == null) {
                state.layoutListener = (changed, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> {
                    try {
                        ApplyResult latest = state.result;
                        if (latest != null) {
                            apply(state, latest);
                        }
                    } catch (Throwable failure) {
                        failure("ruby_layout_failed", failure);
                    }
                };
                view.addOnLayoutChangeListener(state.layoutListener);
            }
            apply(state, result);
        } catch (Throwable failure) {
            failure("ruby_application_failed", failure);
        }
    }

    private TextView currentView(ViewState state, Input input) {
        TextView view = state.view.get();
        if (view == null || protectedText(view)) {
            return null;
        }
        synchronized (states) {
            if (states.get(view) != state || state.generation != input.generation) {
                return null;
            }
        }
        CharSequence current = view.getText();
        if (!(current instanceof Spannable) || current != input.text.get()
                || current.length() != input.originalLength
                || !TextUtils.regionMatches(current, 0, input.snapshot, 0, input.snapshot.length())) {
            return null;
        }
        return view;
    }

    private void apply(ViewState state, ApplyResult result) {
        TextView view = currentView(state, result.input);
        if (view == null) {
            return;
        }
        int width = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        float textSize = view.getTextSize();
        if (width <= 0 || state.appliedGeneration == result.input.generation
                && width == state.appliedWidth && textSize == state.appliedTextSize) {
            return;
        }
        Spannable text = (Spannable) view.getText();
        List<RubySegment> fitting = new ArrayList<>();
        for (RubySegment segment : result.segments) {
            TextPaint paint = new TextPaint();
            paint.set(view.getPaint());
            MetricAffectingSpan[] metrics = text.getSpans(segment.getStartUtf16(),
                    segment.getEndUtf16(), MetricAffectingSpan.class);
            for (MetricAffectingSpan span : metrics) {
                if (!(span instanceof android.text.style.ReplacementSpan)
                        && text.getSpanStart(span) <= segment.getStartUtf16()
                        && text.getSpanEnd(span) >= segment.getEndUtf16()) {
                    span.updateMeasureState(paint);
                }
            }
            if (RubySpan.computeRequiredWidth(paint, text, segment.getStartUtf16(),
                    segment.getEndUtf16(), segment, STYLE) <= width) {
                fitting.add(segment);
            }
        }
        // 先标记尺寸，span 通知引发的后续布局不会再次执行相同应用。
        state.appliedGeneration = result.input.generation;
        state.appliedWidth = width;
        state.appliedTextSize = textSize;
        renderer.apply(text, fitting, STYLE);
        logApplied(result.input, text.getSpans(0, text.length(), RubySpan.class).length,
                result.analysisMs);
    }

    private static void removeLayoutListener(TextView view, ViewState state) {
        if (state.layoutListener != null) {
            view.removeOnLayoutChangeListener(state.layoutListener);
            state.layoutListener = null;
        }
    }

    private void logObservation(Input input, boolean candidate) throws java.io.IOException {
        if (!BuildConfig.DETECTION_LOGS || !logs.acquire(SystemClock.elapsedRealtime())) {
            return;
        }
        StringWriter output = new StringWriter();
        try (JsonWriter json = new JsonWriter(output)) {
            json.beginObject();
            json.name("event").value("text_observed");
            writeMetadata(json, input);
            json.name("sampleCodePoints").value(input.snapshot.codePointCount(0, input.snapshot.length()));
            json.name("containsKanjiCandidate").value(candidate);
            json.name("language").value("undetermined");
            json.name("truncated").value(input.originalLength != input.snapshot.length());
            json.name("omittedSinceLastLog").value(skipped.getAndSet(0));
            json.endObject();
        }
        log.event(output.toString());
    }

    private void logApplied(Input input, int segmentCount, long analysisMs) {
        if (!BuildConfig.DETECTION_LOGS || !applicationLogs.acquire(SystemClock.elapsedRealtime())) {
            return;
        }
        try {
            StringWriter output = new StringWriter();
            try (JsonWriter json = new JsonWriter(output)) {
                json.beginObject();
                json.name("event").value("ruby_applied");
                writeMetadata(json, input);
                json.name("segmentCount").value(segmentCount);
                json.name("analysisMs").value(analysisMs);
                json.name("generation").value(input.generation);
                json.endObject();
            }
            log.event(output.toString());
        } catch (Throwable failure) {
            failure("ruby_logging_failed", failure);
        }
    }

    private void writeMetadata(JsonWriter json, Input input) throws java.io.IOException {
        json.name("package").value(packageName);
        json.name("process").value(processName);
        json.name("viewClass").value(input.viewClass);
        json.name("viewId").value(input.viewId);
        json.name("utf16Length").value(input.originalLength);
        json.name("sampleUtf16Length").value(input.snapshot.length());
        json.name("textExcerptLength").value(0);
    }

    private static final class ViewState {
        final WeakReference<TextView> view;
        long generation;
        Input input;
        ApplyResult result;
        View.OnLayoutChangeListener layoutListener;
        long appliedGeneration = -1;
        int appliedWidth;
        float appliedTextSize;

        ViewState(TextView view) {
            this.view = new WeakReference<>(view);
        }
    }

    private static final class Input {
        final String snapshot;
        final WeakReference<CharSequence> text;
        final int originalLength;
        final boolean spannable;
        final long generation;
        final int viewId;
        final String viewClass;

        Input(String snapshot, CharSequence text, long generation, int viewId, String viewClass) {
            this.snapshot = snapshot;
            this.text = new WeakReference<>(text);
            originalLength = text.length();
            spannable = text instanceof Spannable;
            this.generation = generation;
            this.viewId = viewId;
            this.viewClass = viewClass;
        }
    }

    private static final class ApplyResult {
        final Input input;
        final List<RubySegment> segments;
        final long analysisMs;

        ApplyResult(Input input, List<RubySegment> segments, long analysisMs) {
            this.input = input;
            this.segments = segments;
            this.analysisMs = analysisMs;
        }
    }
}

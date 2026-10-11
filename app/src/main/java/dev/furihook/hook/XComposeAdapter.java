package dev.furihook.hook;

import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.furihook.BuildConfig;
import dev.furihook.config.DetectionConfig;
import dev.furihook.engine.JapaneseDetector;
import dev.furihook.engine.ReadingEngine;
import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubySpan;
import dev.furihook.renderer.RubyStyle;
import dev.furihook.renderer.RubyTextRenderer;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** X 12.31.0 的 R8 名称适配器，按 APK 中核实的 AndroidX Compose 描述符注册。 */
public final class XComposeAdapter {
    private XComposeAdapter() {
    }

    public static XposedInterface.HookHandle[] register(XposedModule module, ModuleLog log,
            ClassLoader loader, ReadingEngine engine) throws Throwable {
        if (module == null || log == null || loader == null || engine == null) {
            throw new IllegalArgumentException("module、log、loader 和 engine 必须存在");
        }
        try {
            return new Coordinator(module, log, loader, engine).install();
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException mismatch) {
            log.event("{\"event\":\"x_compose_unsupported\",\"reason\":\"signature_mismatch\"}");
            return new XposedInterface.HookHandle[0];
        }
    }

    private static final class Coordinator {
        private static final int CACHE_LIMIT = 64;
        private static final int NODE_LIMIT = 256;
        private static final int MAX_TEXT = DetectionConfig.MAX_SNAPSHOT_UTF16;
        private static final RubyStyle STYLE = new RubyStyle() {
            @Override public float getTextSizeScale() { return 0.5f; }
            @Override public float getVerticalOffsetEm() { return 0f; }
            @Override public float getInterlinearSpacingEm() { return 0.08f; }
        };

        private final XposedModule module;
        private final ModuleLog log;
        private final ReadingEngine engine;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final RubyTextRenderer renderer = new RubyTextRenderer();
        private final WindowRateLimiter logLimiter =
                new WindowRateLimiter(DetectionConfig.LOGS_PER_SECOND);
        private final ThreadLocal<MeasureContext> currentMeasure = new ThreadLocal<>();
        private final Map<Object, NodeState> nodes = new WeakHashMap<>();
        private final Map<String, List<RubySegment>> readings =
                new LinkedHashMap<String, List<RubySegment>>(16, 0.75f, true);
        private final Map<String, Boolean> pending = new LinkedHashMap<>();
        private final ThreadPoolExecutor worker;

        private final Class<?> intrinsicsClass;
        private final Class<?> annotatedNodeClass;
        private final Class<?> simpleNodeClass;
        private final Class<?> annotatedStringClass;
        private final Class<?> androidTextPaintClass;
        private final Class<?> androidIntrinsicsClass;
        private final Class<?> intrinsicScopeClass;
        private final Class<?> intrinsicMeasurableClass;
        private final Field annotatedNodeText;
        private final Field simpleNodeText;
        private final Field intrinsicsSource;
        private final Field intrinsicsText;
        private final Field intrinsicsCache;
        private final Field intrinsicsPaint;
        private final Field intrinsicsDirection;
        private final Field nodeSelection;
        private final Field simpleNodeCache;
        private final Field annotatedStringPlainText;
        private final Method annotatedInvalidate;
        private final Method[] simpleInvalidate;
        private final Method simpleCacheClear;
        private final Method maxWidth;
        private final Constructor<?> intrinsicsCacheConstructor;
        private final Constructor<?> intrinsicsConstructor;
        private final Method annotatedMeasure;
        private final Method simpleMeasure;

        Coordinator(XposedModule module, ModuleLog log, ClassLoader loader,
                ReadingEngine engine) throws Throwable {
            this.module = module;
            this.log = log;
            this.engine = engine;
            intrinsicsClass = Class.forName("androidx.compose.ui.text.b", false, loader);
            annotatedNodeClass = Class.forName(
                    "androidx.compose.foundation.text.modifiers.u", false, loader);
            simpleNodeClass = Class.forName(
                    "androidx.compose.foundation.text.modifiers.y", false, loader);
            annotatedStringClass = Class.forName("androidx.compose.ui.text.h", false, loader);
            androidTextPaintClass = Class.forName(
                    "androidx.compose.ui.text.platform.e", false, loader);
            androidIntrinsicsClass = Class.forName(
                    "androidx.compose.ui.text.android.m", false, loader);
            Class<?> constraintsClass = Class.forName(
                    "androidx.compose.ui.unit.a", false, loader);
            Class<?> measureScope = Class.forName("androidx.compose.ui.layout.c1", false, loader);
            Class<?> measurable = Class.forName("androidx.compose.ui.layout.z0", false, loader);
            intrinsicScopeClass = Class.forName("androidx.compose.ui.node.p0", false, loader);
            intrinsicMeasurableClass = measurable;
            Class<?> measureResult = Class.forName("androidx.compose.ui.layout.b1", false, loader);
            Class<?> textStyle = Class.forName("androidx.compose.ui.text.c1", false, loader);
            Class<?> fontResolver = Class.forName("androidx.compose.ui.text.font.i", false, loader);
            Class<?> density = Class.forName("androidx.compose.ui.unit.c", false, loader);

            intrinsicsConstructor = intrinsicsClass.getDeclaredConstructor(String.class,
                    textStyle, List.class, List.class, fontResolver, density, boolean.class);
            intrinsicsConstructor.setAccessible(true);
            intrinsicsSource = field(intrinsicsClass, "a", String.class);
            intrinsicsText = field(intrinsicsClass, "h", CharSequence.class);
            intrinsicsCache = field(intrinsicsClass, "i", androidIntrinsicsClass);
            intrinsicsPaint = field(intrinsicsClass, "g", androidTextPaintClass);
            intrinsicsDirection = field(intrinsicsClass, "l", int.class);
            intrinsicsCacheConstructor = androidIntrinsicsClass.getDeclaredConstructor(
                    CharSequence.class, TextPaint.class, int.class);
            intrinsicsCacheConstructor.setAccessible(true);

            Field annotatedText = field(annotatedNodeClass, "o", annotatedStringClass);
            annotatedNodeText = annotatedText;
            simpleNodeText = field(simpleNodeClass, "o", String.class);
            Class<?> simpleCacheClass = Class.forName(
                    "androidx.compose.foundation.text.modifiers.g", false, loader);
            simpleNodeCache = field(simpleNodeClass, "x", simpleCacheClass);
            simpleCacheClear = exactMethod(simpleCacheClass, "c", void.class);
            nodeSelection = field(annotatedNodeClass, "y",
                    Class.forName("androidx.compose.foundation.text.modifiers.k", false, loader));
            annotatedStringPlainText = field(annotatedStringClass, "b", String.class);
            annotatedInvalidate = exactMethod(annotatedNodeClass, "u1", void.class,
                    boolean.class, boolean.class, boolean.class, boolean.class);
            annotatedMeasure = exactMethod(annotatedNodeClass, "b", measureResult,
                    measureScope, measurable, long.class);
            simpleMeasure = exactMethod(simpleNodeClass, "b", measureResult,
                    measureScope, measurable, long.class);
            Class<?> nodeInterface = Class.forName("androidx.compose.ui.node.y", false, loader);
            Class<?> drawNodeInterface = Class.forName("androidx.compose.ui.node.o", false, loader);
            Class<?> nodeInvalidations = Class.forName("androidx.compose.ui.node.d", false, loader);
            Method requestRemeasure = exactMethod(nodeInvalidations, "k", void.class,
                    nodeInterface);
            Method requestRedraw = exactMethod(nodeInvalidations, "i", void.class,
                    drawNodeInterface);
            simpleInvalidate = new Method[] {requestRemeasure, requestRedraw};
            maxWidth = exactMethod(constraintsClass, "i", int.class, long.class);

            worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(CACHE_LIMIT), runnable -> {
                        Thread thread = new Thread(runnable, "FuriHook-X-compose-reading");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            worker.allowCoreThreadTimeOut(true);
        }

        XposedInterface.HookHandle[] install() throws Throwable {
            List<XposedInterface.HookHandle> handles = new ArrayList<>();
            try {
                boolean deoptimized = module.deoptimize(intrinsicsConstructor);
                handles.add(hook(intrinsicsConstructor, "paragraph-intrinsics",
                        chain -> afterIntrinsics(chain)));
                handles.add(hook(annotatedMeasure, "annotated-measure",
                        chain -> aroundMeasure(chain, true)));
                handles.add(hook(simpleMeasure, "simple-measure",
                        chain -> aroundMeasure(chain, false)));
                installIntrinsicHooks(handles, annotatedNodeClass, "annotated", true);
                installIntrinsicHooks(handles, simpleNodeClass, "simple", false);
                log.event("{\"event\":\"x_compose_hooks_registered\",\"nodes\":2"
                        + ",\"constructorDeoptimized\":" + deoptimized + "}");
                return handles.toArray(new XposedInterface.HookHandle[0]);
            } catch (Throwable failure) {
                for (XposedInterface.HookHandle handle : handles) {
                    handle.unhook();
                }
                throw failure;
            }
        }

        private XposedInterface.HookHandle hook(Constructor<?> constructor, String id,
                XposedInterface.Hooker hooker) {
            return module.hook(constructor).setId("dev.furihook.xcompose." + id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
        }

        private XposedInterface.HookHandle hook(Method method, String id,
                XposedInterface.Hooker hooker) {
            return module.hook(method).setId("dev.furihook.xcompose." + id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
        }

        private Object aroundMeasure(XposedInterface.Chain chain, boolean annotated)
                throws Throwable {
            Object node = chain.getThisObject();
            long constraints = (Long) chain.getArg(2);
            int width = ((Number) maxWidth.invoke(null, constraints)).intValue();
            MeasureContext previous = currentMeasure.get();
            currentMeasure.set(new MeasureContext(node, annotated, width, true));
            try {
                Object result = chain.proceed();
                scheduleConstrainedRebuildIfNeeded(node, annotated);
                return result;
            } finally {
                if (previous == null) currentMeasure.remove();
                else currentMeasure.set(previous);
            }
        }

        private void installIntrinsicHooks(List<XposedInterface.HookHandle> handles,
                Class<?> nodeClass, String label, boolean annotated) throws Throwable {
            for (String name : new String[] {"B", "Y", "h0", "v0"}) {
                Method method = exactMethod(nodeClass, name, int.class,
                        intrinsicScopeClass, intrinsicMeasurableClass, int.class);
                handles.add(hook(method, label + "-intrinsic-" + name,
                        chain -> aroundIntrinsic(chain, annotated)));
            }
        }

        private Object aroundIntrinsic(XposedInterface.Chain chain, boolean annotated)
                throws Throwable {
            MeasureContext previous = currentMeasure.get();
            currentMeasure.set(new MeasureContext(chain.getThisObject(), annotated,
                    Integer.MAX_VALUE, false));
            try {
                return chain.proceed();
            } finally {
                if (previous == null) currentMeasure.remove();
                else currentMeasure.set(previous);
            }
        }

        private Object afterIntrinsics(XposedInterface.Chain chain) throws Throwable {
            Object proceedResult = chain.proceed();
            Object instance = chain.getThisObject();
            if (instance == null) return proceedResult;
            try {
                adaptIntrinsics(instance);
            } catch (Throwable failure) {
                failure("x_compose_adaptation_failed", failure);
            }
            return proceedResult;
        }

        private void adaptIntrinsics(Object instance) throws Throwable {
            MeasureContext context = currentMeasure.get();
            if (context == null) return;
            String source = (String) intrinsicsSource.get(instance);
            if (source == null || source.isEmpty() || source.length() > MAX_TEXT
                    || !JapaneseDetector.containsKanjiCandidate(source)) return;
            if (!isCurrentNodeText(context)) return;
            track(context, source);
            if (!context.constrained) {
                enqueue(source);
                return;
            }
            List<RubySegment> segments = cached(source);
            if (segments == null) {
                enqueue(source);
                return;
            }
            if (segments.isEmpty()) return;

            CharSequence original = (CharSequence) intrinsicsText.get(instance);
            if (original == null || original.length() != source.length()
                    || !source.contentEquals(original)) return;
            Spannable text = new SpannableString(original);
            TextPaint paint = copyPaint((TextPaint) intrinsicsPaint.get(instance));
            List<RubySegment> fitting = fit(text, segments, paint, context.maxWidth);
            if (fitting.isEmpty()) return;
            renderer.apply(text, fitting, STYLE);
            addLineHeights(text, paint);
            if (!renderer.hasRuby(text)) return;
            Object originalCache = intrinsicsCache.get(instance);
            Object replacementCache = intrinsicsCacheConstructor.newInstance(text,
                    intrinsicsPaint.get(instance), intrinsicsDirection.getInt(instance));
            try {
                intrinsicsText.set(instance, text);
                intrinsicsCache.set(instance, replacementCache);
            } catch (Throwable commitFailure) {
                intrinsicsText.set(instance, original);
                intrinsicsCache.set(instance, originalCache);
                throw commitFailure;
            }
            if (BuildConfig.DETECTION_LOGS
                    && logLimiter.acquire(SystemClock.elapsedRealtime())) {
                log.event("{\"event\":\"x_compose_ruby_applied\",\"segmentCount\":"
                        + text.getSpans(0, text.length(), RubySpan.class).length + "}");
            }
        }

        private boolean isCurrentNodeText(MeasureContext context) throws Throwable {
            if (!context.annotated) return true;
            Object selection = nodeSelection.get(context.node);
            return selection == null;
        }

        private String nodeText(Object node, boolean annotated) throws Throwable {
            if (!annotated) return (String) simpleNodeText.get(node);
            Object annotatedString = annotatedNodeText.get(node);
            return (String) annotatedStringPlainText.get(annotatedString);
        }

        private void track(MeasureContext context, String paragraph) throws Throwable {
            synchronized (nodes) {
                NodeState state = nodes.get(context.node);
                if (state == null) {
                    state = new NodeState(context.node, context.annotated);
                    nodes.put(context.node, state);
                }
                String fullText = nodeText(context.node, context.annotated);
                if (fullText == null) return;
                if (!fullText.equals(state.fullText)) {
                    state.fullText = fullText;
                    state.paragraphs.clear();
                    state.needsConstrainedRebuild = false;
                    state.constrainedRebuildAttempted = false;
                }
                state.addParagraph(paragraph);
                if (!context.constrained && !state.constrainedRebuildAttempted
                        && cached(paragraph) != null) {
                    state.needsConstrainedRebuild = true;
                }
                while (nodes.size() > NODE_LIMIT) {
                    Iterator<Object> iterator = nodes.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
            }
        }

        private void scheduleConstrainedRebuildIfNeeded(Object node, boolean annotated) {
            String fullText;
            List<String> paragraphs;
            synchronized (nodes) {
                NodeState state = nodes.get(node);
                if (state == null || !state.needsConstrainedRebuild
                        || state.constrainedRebuildAttempted) return;
                try {
                    fullText = nodeText(node, annotated);
                } catch (Throwable failure) {
                    failure("x_compose_node_check_failed", failure);
                    return;
                }
                if (fullText == null || !fullText.equals(state.fullText)) return;
                paragraphs = state.liveParagraphs();
                if (paragraphs.isEmpty()) return;
                for (String paragraph : paragraphs) {
                    if (cached(paragraph) == null) return;
                }
                state.needsConstrainedRebuild = false;
                state.constrainedRebuildAttempted = true;
            }
            main.post(() -> {
                try {
                    if (!fullText.equals(nodeText(node, annotated))) return;
                    if (annotated) {
                        annotatedInvalidate.invoke(node, false, true, true, false);
                    } else {
                        Object cache = simpleNodeCache.get(node);
                        if (cache != null) simpleCacheClear.invoke(cache);
                        simpleInvalidate[0].invoke(null, node);
                        simpleInvalidate[1].invoke(null, node);
                    }
                } catch (Throwable failure) {
                    failure("x_compose_constrained_rebuild_failed", failure);
                }
            });
        }

        private List<RubySegment> fit(Spannable text, List<RubySegment> segments,
                TextPaint base, int width) {
            List<RubySegment> result = new ArrayList<>();
            Spanned spanned = (Spanned) text;
            TextPaint paint = new TextPaint();
            for (RubySegment segment : segments) {
                segment.validate(text);
                int start = segment.getStartUtf16();
                int end = segment.getEndUtf16();
                paint.set(base);
                boolean conflict = false;
                MetricAffectingSpan[] styles = spanned.getSpans(start, end,
                        MetricAffectingSpan.class);
                for (MetricAffectingSpan style : styles) {
                    int spanStart = spanned.getSpanStart(style);
                    int spanEnd = spanned.getSpanEnd(style);
                    if (spanStart < end && spanEnd > start
                            && !(spanStart <= start && spanEnd >= end)) {
                        conflict = true;
                        break;
                    }
                    if (!(style instanceof ReplacementSpan)
                            && spanStart <= start && spanEnd >= end) {
                        style.updateMeasureState(paint);
                    }
                }
                if (!conflict && RubySpan.computeRequiredWidth(paint, text, start, end,
                        segment, STYLE) <= width) {
                    result.add(segment);
                }
            }
            return result;
        }

        private void addLineHeights(Spannable text, TextPaint paint) {
            Spanned spanned = (Spanned) text;
            for (RubySpan span : spanned.getSpans(0, text.length(), RubySpan.class)) {
                Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
                int start = spanned.getSpanStart(span);
                int end = spanned.getSpanEnd(span);
                span.getSize(paint, text, start, end, metrics);
                text.setSpan(new RubyHeight(metrics), start, end,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
        }

        private static TextPaint copyPaint(TextPaint paint) {
            TextPaint copy = new TextPaint();
            copy.set(paint);
            return copy;
        }

        private void failure(String event, Throwable failure) {
            if (BuildConfig.DETECTION_LOGS
                    && logLimiter.acquire(SystemClock.elapsedRealtime())) {
                log.failure(event, failure);
            }
        }

        private List<RubySegment> cached(String source) {
            synchronized (readings) { return readings.get(source); }
        }

        private void enqueue(String source) {
            synchronized (pending) {
                synchronized (readings) {
                    if (readings.containsKey(source)) return;
                }
                if (pending.containsKey(source)) return;
                pending.put(source, Boolean.TRUE);
            }
            try {
                worker.execute(() -> analyze(source));
            } catch (RuntimeException queueFull) {
                synchronized (pending) { pending.remove(source); }
                failure("x_compose_queue_rejected", queueFull);
            }
        }

        private void analyze(String source) {
            List<RubySegment> result;
            try {
                result = Collections.unmodifiableList(new ArrayList<>(engine.analyze(source)));
                int previousEnd = -1;
                for (RubySegment segment : result) {
                    segment.validate(source);
                    if (segment.getStartUtf16() < previousEnd) {
                        throw new IllegalArgumentException("ReadingEngine ranges overlap");
                    }
                    previousEnd = segment.getEndUtf16();
                }
            } catch (Throwable failure) {
                synchronized (pending) { pending.remove(source); }
                failure("x_compose_reading_failed", failure);
                return;
            }
            synchronized (readings) {
                readings.put(source, result);
                while (readings.size() > CACHE_LIMIT) {
                    readings.remove(readings.keySet().iterator().next());
                }
            }
            synchronized (pending) { pending.remove(source); }
            if (!result.isEmpty()) main.post(() -> invalidate(source));
        }

        private void invalidate(String source) {
            List<NodeState> matches = new ArrayList<>();
            synchronized (nodes) {
                Iterator<Map.Entry<Object, NodeState>> iterator = nodes.entrySet().iterator();
                while (iterator.hasNext()) {
                    NodeState state = iterator.next().getValue();
                    Object node = state.node.get();
                    if (node == null) {
                        iterator.remove();
                        continue;
                    }
                    try {
                        if (state.contains(source)
                                && state.fullText.equals(nodeText(node, state.annotated))) {
                            matches.add(state);
                        }
                    } catch (Throwable failure) {
                        failure("x_compose_node_check_failed", failure);
                    }
                }
            }
            for (NodeState state : matches) {
                Object node = state.node.get();
                if (node == null) continue;
                try {
                    if (state.annotated) annotatedInvalidate.invoke(node, false, true, true, true);
                    else {
                        Object cache = simpleNodeCache.get(node);
                        if (cache != null) simpleCacheClear.invoke(cache);
                        simpleInvalidate[0].invoke(null, node);
                        simpleInvalidate[1].invoke(null, node);
                    }
                } catch (Throwable failure) {
                    failure("x_compose_invalidation_failed", failure);
                }
            }
        }

        private static Method exactMethod(Class<?> owner, String name, Class<?> result,
                Class<?>... parameters) throws NoSuchMethodException {
            Method method = owner.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != result) throw new NoSuchMethodException(name + " return");
            method.setAccessible(true);
            return method;
        }

        private static Field field(Class<?> owner, String name, Class<?> type)
                throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != type) throw new NoSuchFieldException(owner.getName() + "." + name);
            field.setAccessible(true);
            return field;
        }
    }

    private static final class MeasureContext {
        final Object node;
        final boolean annotated;
        final int maxWidth;
        final boolean constrained;
        MeasureContext(Object node, boolean annotated, int maxWidth, boolean constrained) {
            this.node = node;
            this.annotated = annotated;
            this.maxWidth = maxWidth;
            this.constrained = constrained;
        }
    }

    private static final class NodeState {
        final WeakReference<Object> node;
        final boolean annotated;
        final List<WeakReference<String>> paragraphs = new ArrayList<>();
        String fullText;
        boolean needsConstrainedRebuild;
        boolean constrainedRebuildAttempted;
        NodeState(Object node, boolean annotated) {
            this.node = new WeakReference<>(node);
            this.annotated = annotated;
        }
        void addParagraph(String paragraph) {
            for (Iterator<WeakReference<String>> iterator = paragraphs.iterator(); iterator.hasNext();) {
                String old = iterator.next().get();
                if (old == null) iterator.remove();
                else if (old.equals(paragraph)) return;
            }
            paragraphs.add(new WeakReference<>(paragraph));
            while (paragraphs.size() > 32) paragraphs.remove(0);
        }
        boolean contains(String paragraph) {
            for (Iterator<WeakReference<String>> iterator = paragraphs.iterator(); iterator.hasNext();) {
                String old = iterator.next().get();
                if (old == null) iterator.remove();
                else if (old.equals(paragraph)) return true;
            }
            return false;
        }
        List<String> liveParagraphs() {
            List<String> live = new ArrayList<>();
            for (Iterator<WeakReference<String>> iterator = paragraphs.iterator(); iterator.hasNext();) {
                String paragraph = iterator.next().get();
                if (paragraph == null) iterator.remove();
                else live.add(paragraph);
            }
            return live;
        }
    }

    private static final class RubyHeight implements LineHeightSpan {
        private final Paint.FontMetricsInt ruby = new Paint.FontMetricsInt();
        RubyHeight(Paint.FontMetricsInt source) {
            ruby.top = source.top;
            ruby.ascent = source.ascent;
            ruby.descent = source.descent;
            ruby.bottom = source.bottom;
            ruby.leading = source.leading;
        }
        @Override public void chooseHeight(CharSequence text, int start, int end,
                int spanstartv, int v, Paint.FontMetricsInt fm) {
            fm.top = Math.min(fm.top, ruby.top);
            fm.ascent = Math.min(fm.ascent, ruby.ascent);
            fm.descent = Math.max(fm.descent, ruby.descent);
            fm.bottom = Math.max(fm.bottom, ruby.bottom);
        }
    }
}

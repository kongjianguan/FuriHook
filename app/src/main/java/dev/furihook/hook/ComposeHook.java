package dev.furihook.hook;

import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.furihook.config.DetectionConfig;
import dev.furihook.BuildConfig;
import dev.furihook.engine.JapaneseDetector;
import dev.furihook.engine.ReadingEngine;
import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubyStyle;
import dev.furihook.renderer.RubySpan;
import dev.furihook.renderer.RubyTextRenderer;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Hooks Compose's Android text conversion and invalidates its text layout after local analysis. */
public final class ComposeHook {
    private static final String HELPER_CLASS =
            "androidx.compose.ui.text.platform.AndroidParagraphHelper_androidKt";
    private static final String INTRINSICS_CLASS =
            "androidx.compose.ui.text.platform.AndroidParagraphIntrinsics";
    private static final String ANNOTATED_NODE_CLASS =
            "androidx.compose.foundation.text.modifiers.TextAnnotatedStringNode";
    private static final String SIMPLE_NODE_CLASS =
            "androidx.compose.foundation.text.modifiers.TextStringSimpleNode";

    private ComposeHook() {
    }

    public static XposedInterface.HookHandle[] register(XposedModule module, ModuleLog log,
            ClassLoader loader, ReadingEngine engine) throws Throwable {
        if (module == null || log == null || loader == null || engine == null) {
            throw new IllegalArgumentException("module, log, loader 和 engine 必须存在");
        }

        final Class<?> helper;
        final Class<?> intrinsics;
        final Class<?> annotatedNode;
        final Class<?> simpleNode;
        try {
            helper = Class.forName(HELPER_CLASS, false, loader);
            intrinsics = Class.forName(INTRINSICS_CLASS, false, loader);
            annotatedNode = Class.forName(ANNOTATED_NODE_CLASS, false, loader);
            simpleNode = Class.forName(SIMPLE_NODE_CLASS, false, loader);
        } catch (ClassNotFoundException missingCompose) {
            log.event("{\"event\":\"compose_unsupported\",\"reason\":\"classes_absent\"}");
            return new XposedInterface.HookHandle[0];
        }

        try {
            Coordinator coordinator = new Coordinator(module, log, loader, engine,
                    intrinsics, annotatedNode, simpleNode);
            return coordinator.install();
        } catch (NoSuchMethodException | NoSuchFieldException unsupportedSignature) {
            log.event("{\"event\":\"compose_unsupported\",\"reason\":\"signature_mismatch\"}");
            return new XposedInterface.HookHandle[0];
        }
    }

    private static final class Coordinator {
        private static final int CACHE_CAPACITY = 64;
        private static final int MAX_TEXT_UTF16 = DetectionConfig.MAX_SNAPSHOT_UTF16;
        private static final RubyStyle STYLE = new RubyStyle() {
            @Override public float getTextSizeScale() { return 0.5f; }
            @Override public float getVerticalOffsetEm() { return 0f; }
            @Override public float getInterlinearSpacingEm() { return 0.08f; }
        };

        private final XposedModule module;
        private final ModuleLog log;
        private final ReadingEngine engine;
        private final RubyTextRenderer renderer = new RubyTextRenderer();
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ThreadLocal<Deque<LayoutContext>> layoutContexts =
                ThreadLocal.withInitial(ArrayDeque::new);
        private final Map<Object, NodeState> nodes = new WeakHashMap<>();
        private final WindowRateLimiter logs = new WindowRateLimiter(DetectionConfig.LOGS_PER_SECOND);
        private final ThreadLocal<TextPaint> paragraphPaint = new ThreadLocal<>();
        private final Map<String, List<RubySegment>> readings =
                new LinkedHashMap<String, List<RubySegment>>(16, 0.75f, true);
        private final Set<String> pendingTexts = new HashSet<>();
        private final ThreadPoolExecutor worker;

        private final Method getMaxWidth;
        private final Field annotatedTextField;
        private final Field simpleTextField;
        private final Method annotatedStringGetText;
        private final Method annotatedInvalidations;
        private final Method simpleInvalidations;
        private final Method helperMethod;
        private final Constructor<?> intrinsicsConstructor;
        private final Class<?> androidTextPaint;
        private final Class<?> annotatedNode;
        private final Class<?> simpleNode;

        Coordinator(XposedModule module, ModuleLog log, ClassLoader loader, ReadingEngine engine,
                Class<?> intrinsics, Class<?> annotatedNode, Class<?> simpleNode) throws Throwable {
            this.module = module;
            this.log = log;
            this.engine = engine;
            this.annotatedNode = annotatedNode;
            this.simpleNode = simpleNode;

            Class<?> annotatedString = Class.forName("androidx.compose.ui.text.AnnotatedString",
                    false, loader);
            Class<?> constraints = Class.forName("androidx.compose.ui.unit.Constraints", false, loader);
            Class<?> fontResolver = Class.forName(
                    "androidx.compose.ui.text.font.FontFamily$Resolver", false, loader);
            Class<?> density = Class.forName("androidx.compose.ui.unit.Density", false, loader);
            Class<?> textStyle = Class.forName("androidx.compose.ui.text.TextStyle", false, loader);

            Class<?> function4 = Class.forName("kotlin.jvm.functions.Function4", false, loader);
            helperMethod = exactMethod(Class.forName(HELPER_CLASS, false, loader),
                    "createCharSequence", CharSequence.class, String.class, float.class,
                    textStyle, List.class, List.class, density, function4,
                    boolean.class);
            if (!Modifier.isStatic(helperMethod.getModifiers())) {
                throw new NoSuchMethodException("createCharSequence is not static");
            }
            intrinsicsConstructor = intrinsics.getDeclaredConstructor(String.class, textStyle,
                    List.class, List.class, fontResolver, density);
            androidTextPaint = Class.forName(
                    "androidx.compose.ui.text.platform.AndroidTextPaint", false, loader);
            getMaxWidth = findConstraintsMaxWidth(constraints);
            annotatedTextField = findField(annotatedNode, "text", annotatedString);
            simpleTextField = findField(simpleNode, "text", String.class);
            annotatedStringGetText = annotatedString.getDeclaredMethod("getText");
            annotatedStringGetText.setAccessible(true);
            annotatedInvalidations = exactMethod(annotatedNode, "doInvalidations", void.class,
                    boolean.class, boolean.class, boolean.class, boolean.class);
            simpleInvalidations = exactMethod(simpleNode, "doInvalidations", void.class,
                    boolean.class, boolean.class, boolean.class);

            worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(CACHE_CAPACITY), runnable -> {
                        Thread thread = new Thread(runnable, "FuriHook-Compose-reading");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            worker.allowCoreThreadTimeOut(true);
        }

        XposedInterface.HookHandle[] install() throws Throwable {
            List<XposedInterface.HookHandle> handles = new ArrayList<>();
            try {
                boolean callerDeoptimized = module.deoptimize(intrinsicsConstructor);
                handles.add(hook(androidTextPaint.getDeclaredConstructor(int.class, float.class),
                        "paragraph-paint", chain -> {
                            Object result = chain.proceed();
                            paragraphPaint.set((TextPaint) chain.getThisObject());
                            return result;
                        }));
                handles.add(hook(helperMethod, "char-sequence",
                        chain -> afterCreateCharSequence(chain)));
                installNodeHooks(handles, annotatedNode, true);
                installNodeHooks(handles, simpleNode, false);
                log.event("{\"event\":\"compose_hooks_registered\",\"foundationNodeTypes\":2"
                        + ",\"callerDeoptimized\":" + callerDeoptimized + "}");
                return handles.toArray(new XposedInterface.HookHandle[0]);
            } catch (Throwable failure) {
                for (XposedInterface.HookHandle handle : handles) {
                    try {
                        handle.unhook();
                    } catch (Throwable cleanupFailure) {
                        log.failure("compose_hook_cleanup_failed", cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        private void installNodeHooks(List<XposedInterface.HookHandle> handles,
                Class<?> nodeClass, boolean annotated) throws Throwable {
            Class<?> measureScope = Class.forName("androidx.compose.ui.layout.MeasureScope",
                    false, nodeClass.getClassLoader());
            Class<?> measurable = Class.forName("androidx.compose.ui.layout.Measurable",
                    false, nodeClass.getClassLoader());
            Class<?> intrinsicScope = Class.forName(
                    "androidx.compose.ui.layout.IntrinsicMeasureScope", false,
                    nodeClass.getClassLoader());
            Class<?> intrinsicMeasurable = Class.forName(
                    "androidx.compose.ui.layout.IntrinsicMeasurable", false,
                    nodeClass.getClassLoader());

            Class<?> measureResult = Class.forName("androidx.compose.ui.layout.MeasureResult",
                    false, nodeClass.getClassLoader());
            Method measure = uniqueMethod(nodeClass, name -> name.startsWith("measure-"),
                    measureResult, measureScope, measurable, long.class);
            handles.add(hook(measure, (annotated ? "annotated" : "simple") + "-measure",
                    chain -> aroundNodeLayout(chain, annotated, true)));

            String[] intrinsicNames = {"minIntrinsicWidth", "maxIntrinsicWidth",
                    "minIntrinsicHeight", "maxIntrinsicHeight"};
            for (String name : intrinsicNames) {
                Method intrinsic = exactMethod(nodeClass, name, int.class, intrinsicScope,
                        intrinsicMeasurable, int.class);
                handles.add(hook(intrinsic,
                        (annotated ? "annotated-" : "simple-") + name,
                        chain -> aroundNodeLayout(chain, annotated, false)));
            }
        }

        private XposedInterface.HookHandle hook(Method method, String suffix,
                XposedInterface.Hooker hooker) {
            return module.hook(method)
                    .setId("dev.furihook.compose." + suffix)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
        }

        private XposedInterface.HookHandle hook(Constructor<?> constructor, String suffix,
                XposedInterface.Hooker hooker) {
            return module.hook(constructor)
                    .setId("dev.furihook.compose." + suffix)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
        }

        private Object aroundNodeLayout(XposedInterface.Chain chain, boolean annotated,
                boolean constrainedMeasure) throws Throwable {
            Object node = chain.getThisObject();
            int maxWidth = Integer.MAX_VALUE;
            if (constrainedMeasure) {
                try {
                    maxWidth = maxWidth((Long) chain.getArg(2));
                } catch (Throwable failure) {
                    failure("compose_constraints_read_failed", failure);
                    return chain.proceed();
                }
            }
            Deque<LayoutContext> contexts = layoutContexts.get();
            contexts.push(new LayoutContext(node, annotated, constrainedMeasure, maxWidth));
            try {
                Object result = chain.proceed();
                if (constrainedMeasure) {
                    scheduleConstrainedRebuildIfNeeded(node, annotated);
                }
                return result;
            } finally {
                contexts.pop();
                if (contexts.isEmpty()) {
                    layoutContexts.remove();
                }
            }
        }

        private Object afterCreateCharSequence(XposedInterface.Chain chain) throws Throwable {
            CharSequence original;
            try {
                original = (CharSequence) chain.proceed();
            } catch (Throwable failure) {
                paragraphPaint.remove();
                throw failure;
            }
            try {
                String source = (String) chain.getArg(0);
                Deque<LayoutContext> contexts = layoutContexts.get();
                if (contexts.isEmpty() || source == null || source.isEmpty()
                        || source.length() > MAX_TEXT_UTF16
                        || !JapaneseDetector.containsKanjiCandidate(source)) {
                    if (contexts.isEmpty()) {
                        layoutContexts.remove();
                        paragraphPaint.remove();
                    }
                    return original;
                }

                LayoutContext context = contexts.peek();
                trackNode(context, source);
                if (!context.constrainedMeasure) {
                    enqueue(source);
                    return original;
                }
                if (original == null || original.toString().length() != source.length()
                        || !source.contentEquals(original)) {
                    return original;
                }

                List<RubySegment> cached = cachedReadings(source);
                if (cached == null) {
                    enqueue(source);
                    return original;
                }
                if (cached.isEmpty()) {
                    return original;
                }

                TextPaint initializedPaint = paragraphPaint.get();
                if (initializedPaint == null) {
                    return original;
                }

                Spannable spannable = asSpannable(original);
                List<RubySegment> fitting = fitSegments(spannable, cached, initializedPaint,
                        context.maxWidth);
                if (!fitting.isEmpty()) {
                    for (RubySegment segment : fitting) {
                        spannable.setSpan(
                                new TypefacePreservingSpan(initializedPaint.getTypeface()),
                                segment.getStartUtf16(), segment.getEndUtf16(),
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                    }
                    renderer.apply(spannable, fitting, STYLE);
                    applyRubyLineHeight(spannable, initializedPaint);
                }
                return renderer.hasRuby(spannable) ? spannable : original;
            } catch (Throwable failure) {
                failure("compose_text_adaptation_failed", failure);
                return original;
            } finally {
                paragraphPaint.remove();
            }
        }

        private void trackNode(LayoutContext context, String source) {
            synchronized (nodes) {
                NodeState state = nodes.get(context.node);
                if (state == null) {
                    state = new NodeState(context.node, context.annotated);
                    nodes.put(context.node, state);
                }
                String nodeText = readNodeText(context.node, context.annotated);
                if (nodeText == null) {
                    return;
                }
                if (!nodeText.equals(state.fullText)) {
                    state.fullText = nodeText;
                    state.sources.clear();
                    state.needsConstrainedRebuild = false;
                }
                state.addSource(source);
                while (state.sources.size() > 32) {
                    state.sources.remove(0);
                }
                while (nodes.size() > DetectionConfig.MAX_TRACKED_VIEWS) {
                    Iterator<Object> iterator = nodes.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
                if (!context.constrainedMeasure) {
                    state.needsConstrainedRebuild = true;
                }
            }
        }

        private void scheduleConstrainedRebuildIfNeeded(Object node, boolean annotated) {
            String fullText = readNodeText(node, annotated);
            if (fullText == null) return;
            List<String> sources;
            synchronized (nodes) {
                NodeState state = nodes.get(node);
                if (state == null || !state.needsConstrainedRebuild
                        || !fullText.equals(state.fullText)) {
                    return;
                }
                sources = state.liveSources();
                if (sources.isEmpty()) return;
                for (String source : sources) {
                    if (cachedReadings(source) == null) return;
                }
                state.needsConstrainedRebuild = false;
            }
            main.post(() -> {
                try {
                    if (!fullText.equals(readNodeText(node, annotated))) return;
                    if (annotated) {
                        annotatedInvalidations.invoke(node, false, true, true, false);
                    } else {
                        simpleInvalidations.invoke(node, false, true, true);
                    }
                } catch (Throwable failure) {
                    failure("compose_constrained_rebuild_failed", failure);
                }
            });
        }

        private List<RubySegment> fitSegments(Spannable text, List<RubySegment> segments,
                TextPaint basePaint, int maxWidth) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            List<RubySegment> fitting = new ArrayList<>();
            Spanned spanned = (Spanned) text;
            for (RubySegment segment : segments) {
                segment.validate(text);
                paint.set(basePaint);
                int start = segment.getStartUtf16();
                int end = segment.getEndUtf16();
                MetricAffectingSpan[] metricSpans =
                        spanned.getSpans(start, end, MetricAffectingSpan.class);
                boolean partialMetricStyle = false;
                for (MetricAffectingSpan span : metricSpans) {
                    int spanStart = spanned.getSpanStart(span);
                    int spanEnd = spanned.getSpanEnd(span);
                    if (spanStart < end && spanEnd > start
                            && !(spanStart <= start && spanEnd >= end)) {
                        partialMetricStyle = true;
                        break;
                    }
                    if (!(span instanceof ReplacementSpan)
                            && spanStart <= start && spanEnd >= end) {
                        span.updateMeasureState(paint);
                    }
                }
                if (partialMetricStyle) {
                    continue;
                }
                float required = RubySpan.computeRequiredWidth(paint, text,
                        start, end, segment, STYLE);
                if (required <= maxWidth) {
                    fitting.add(segment);
                }
            }
            return fitting;
        }

        private static void applyRubyLineHeight(Spannable text, TextPaint basePaint) {
            Spanned spanned = text;
            RubySpan[] appliedSpans = spanned.getSpans(0, spanned.length(), RubySpan.class);
            for (RubySpan rubySpan : appliedSpans) {
                int start = spanned.getSpanStart(rubySpan);
                int end = spanned.getSpanEnd(rubySpan);
                TextPaint paint = new TextPaint(basePaint);
                MetricAffectingSpan[] metricSpans =
                        spanned.getSpans(start, end, MetricAffectingSpan.class);
                for (MetricAffectingSpan metricSpan : metricSpans) {
                    int spanStart = spanned.getSpanStart(metricSpan);
                    int spanEnd = spanned.getSpanEnd(metricSpan);
                    if (metricSpan != rubySpan && !(metricSpan instanceof ReplacementSpan)
                            && spanStart <= start && spanEnd >= end) {
                        metricSpan.updateMeasureState(paint);
                    }
                }
                Paint.FontMetricsInt rubyMetrics = paint.getFontMetricsInt();
                rubySpan.getSize(paint, text, start, end, rubyMetrics);
                text.setSpan(new RubyLineHeightSpan(rubyMetrics), start, end,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
        }

        private static Spannable asSpannable(Object value) {
            if (value instanceof Spannable) {
                return (Spannable) value;
            }
            return new SpannableString((CharSequence) value);
        }

        private List<RubySegment> cachedReadings(String source) {
            synchronized (readings) {
                return readings.get(source);
            }
        }

        private void enqueue(String source) {
            synchronized (pendingTexts) {
                synchronized (readings) {
                    if (readings.containsKey(source)) {
                        return;
                    }
                }
                if (!pendingTexts.add(source)) {
                    return;
                }
            }
            try {
                worker.execute(() -> analyze(source));
            } catch (Throwable failure) {
                synchronized (pendingTexts) {
                    pendingTexts.remove(source);
                }
                failure("compose_reading_queue_failed", failure);
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
                        throw new IllegalArgumentException("ReadingEngine ranges overlap or are unordered");
                    }
                    previousEnd = segment.getEndUtf16();
                }
            } catch (Throwable failure) {
                synchronized (pendingTexts) {
                    pendingTexts.remove(source);
                }
                failure("compose_reading_failed", failure);
                return;
            }

            synchronized (readings) {
                readings.put(source, result);
                while (readings.size() > CACHE_CAPACITY) {
                    Iterator<String> iterator = readings.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
            }
            synchronized (pendingTexts) {
                pendingTexts.remove(source);
            }
            if (!result.isEmpty()) {
                main.post(() -> invalidateMatchingNodes(source));
            }
        }

        private void invalidateMatchingNodes(String source) {
            List<NodeState> matching = new ArrayList<>();
            synchronized (nodes) {
                Iterator<Map.Entry<Object, NodeState>> iterator = nodes.entrySet().iterator();
                while (iterator.hasNext()) {
                    NodeState state = iterator.next().getValue();
                    Object node = state.node.get();
                    String observed = state.fullText;
                    if (node == null) {
                        iterator.remove();
                        continue;
                    }
                    if (state.hasSource(source)
                            && observed.equals(readNodeText(node, state.annotated))) {
                        matching.add(state);
                    }
                }
            }
            for (NodeState state : matching) {
                Object node = state.node.get();
                if (node == null) {
                    continue;
                }
                try {
                    if (state.annotated) {
                        annotatedInvalidations.invoke(node, false, true, true, false);
                    } else {
                        simpleInvalidations.invoke(node, false, true, true);
                    }
                } catch (Throwable failure) {
                    failure("compose_layout_invalidation_failed", failure);
                }
            }
        }

        private String readNodeText(Object node, boolean annotated) {
            try {
                Object value = (annotated ? annotatedTextField : simpleTextField).get(node);
                if (value instanceof String) {
                    return (String) value;
                }
                return (String) annotatedStringGetText.invoke(value);
            } catch (Throwable failure) {
                failure("compose_node_text_read_failed", failure);
                return null;
            }
        }

        private int maxWidth(long constraints) throws Throwable {
            return ((Number) getMaxWidth.invoke(null, constraints)).intValue();
        }

        private void failure(String event, Throwable throwable) {
            if (BuildConfig.DETECTION_LOGS && logs.acquire(SystemClock.elapsedRealtime())) {
                log.failure(event, throwable);
            }
        }

        private static Method findConstraintsMaxWidth(Class<?> constraints)
                throws NoSuchMethodException {
            for (Method method : constraints.getDeclaredMethods()) {
                if (method.getName().startsWith("getMaxWidth-impl")
                        && Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == int.class
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0] == long.class) {
                    method.setAccessible(true);
                    return method;
                }
            }
            throw new NoSuchMethodException("Constraints.getMaxWidth-impl(long)");
        }

        private static Method exactMethod(Class<?> owner, String name, Class<?> returnType,
                Class<?>... parameters) throws NoSuchMethodException {
            Method method = owner.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != returnType) {
                throw new NoSuchMethodException(owner.getName() + "." + name + " return type");
            }
            method.setAccessible(true);
            return method;
        }

        private interface NameMatch {
            boolean accepts(String name);
        }

        private static Method uniqueMethod(Class<?> owner, NameMatch nameMatch,
                Class<?> returnType, Class<?>... parameters) throws NoSuchMethodException {
            Method found = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!nameMatch.accepts(method.getName())
                        || method.getReturnType() != returnType
                        || !sameParameters(method.getParameterTypes(), parameters)) {
                    continue;
                }
                if (found != null) {
                    throw new NoSuchMethodException(owner.getName() + " has ambiguous layout methods");
                }
                found = method;
            }
            if (found == null) {
                throw new NoSuchMethodException(owner.getName() + " layout signature");
            }
            found.setAccessible(true);
            return found;
        }

        private static boolean sameParameters(Class<?>[] left, Class<?>[] right) {
            if (left.length != right.length) {
                return false;
            }
            for (int index = 0; index < left.length; index++) {
                if (left[index] != right[index]) {
                    return false;
                }
            }
            return true;
        }

        private static Field findField(Class<?> owner, String name, Class<?> expectedType)
                throws NoSuchFieldException {
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (field.getType() != expectedType) {
                        throw new NoSuchFieldException(owner.getName() + "." + name + " type");
                    }
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException missing) {
                    if (current == owner) {
                        continue;
                    }
                }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }

        private static final class LayoutContext {
            final Object node;
            final boolean annotated;
            final boolean constrainedMeasure;
            final int maxWidth;

            LayoutContext(Object node, boolean annotated, boolean constrainedMeasure,
                    int maxWidth) {
                this.node = node;
                this.annotated = annotated;
                this.constrainedMeasure = constrainedMeasure;
                this.maxWidth = maxWidth;
            }
        }

        private static final class NodeState {
            final WeakReference<Object> node;
            final boolean annotated;
            String fullText;
            final List<WeakReference<String>> sources = new ArrayList<>();
            boolean needsConstrainedRebuild;

            NodeState(Object node, boolean annotated) {
                this.node = new WeakReference<>(node);
                this.annotated = annotated;
            }

            void addSource(String source) {
                for (Iterator<WeakReference<String>> iterator = sources.iterator();
                        iterator.hasNext();) {
                    String previous = iterator.next().get();
                    if (previous == null) {
                        iterator.remove();
                    } else if (previous.equals(source)) {
                        return;
                    }
                }
                sources.add(new WeakReference<>(source));
            }

            boolean hasSource(String source) {
                for (Iterator<WeakReference<String>> iterator = sources.iterator();
                        iterator.hasNext();) {
                    String paragraph = iterator.next().get();
                    if (paragraph == null) {
                        iterator.remove();
                    } else if (paragraph.equals(source)) {
                        return true;
                    }
                }
                return false;
            }

            List<String> liveSources() {
                List<String> result = new ArrayList<>();
                for (Iterator<WeakReference<String>> iterator = sources.iterator();
                        iterator.hasNext();) {
                    String source = iterator.next().get();
                    if (source == null) {
                        iterator.remove();
                    } else {
                        result.add(source);
                    }
                }
                return result;
            }
        }

        private static final class RubyLineHeightSpan implements LineHeightSpan {
            private final Paint.FontMetricsInt ruby;

            RubyLineHeightSpan(Paint.FontMetricsInt ruby) {
                this.ruby = new Paint.FontMetricsInt();
                this.ruby.top = ruby.top;
                this.ruby.ascent = ruby.ascent;
                this.ruby.descent = ruby.descent;
                this.ruby.bottom = ruby.bottom;
                this.ruby.leading = ruby.leading;
            }

            @Override public void chooseHeight(CharSequence text, int start, int end,
                    int spanstartv, int v, Paint.FontMetricsInt fm) {
                fm.top = Math.min(fm.top, ruby.top);
                fm.ascent = Math.min(fm.ascent, ruby.ascent);
                fm.descent = Math.max(fm.descent, ruby.descent);
                fm.bottom = Math.max(fm.bottom, ruby.bottom);
            }
        }

        private static final class TypefacePreservingSpan extends MetricAffectingSpan {
            private final android.graphics.Typeface typeface;

            TypefacePreservingSpan(android.graphics.Typeface typeface) {
                this.typeface = typeface;
            }

            @Override public void updateMeasureState(TextPaint paint) {
                paint.setTypeface(typeface);
            }

            @Override public void updateDrawState(TextPaint paint) {
                paint.setTypeface(typeface);
            }
        }
    }
}

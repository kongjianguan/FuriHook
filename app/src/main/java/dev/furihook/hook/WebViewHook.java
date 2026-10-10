package dev.furihook.hook;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dev.furihook.engine.ReadingEngine;
import dev.furihook.engine.RubySegment;
import dev.furihook.BuildConfig;
import dev.furihook.config.DetectionConfig;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Adds local Ruby markup to eligible Japanese text nodes in WebView documents. */
public final class WebViewHook {
    private WebViewHook() {
    }

    public static XposedInterface.HookHandle[] register(XposedModule module, ModuleLog log,
            String packageName, String processName, ReadingEngine engine) throws Throwable {
        Coordinator coordinator = new Coordinator(module, log, packageName, processName, engine);
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        coordinator.installClientHooks(WebViewClient.class);
        handles.add(hook(module, WebView.class.getDeclaredMethod("loadUrl", String.class),
                "load-url", chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("loadUrl", String.class, Map.class),
                "load-url-headers", chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("postUrl", String.class, byte[].class),
                "post-url", chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("loadData", String.class,
                String.class, String.class), "load-data", chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("loadDataWithBaseURL", String.class,
                String.class, String.class, String.class, String.class), "load-data-base-url",
                chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("reload"), "reload",
                chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("goBack"), "go-back",
                chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("goForward"), "go-forward",
                chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("goBackOrForward", int.class),
                "go-back-or-forward", chain -> coordinator.afterNavigation(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class),
                "set-webview-client", chain -> coordinator.afterSetClient(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("destroy"), "destroy",
                chain -> coordinator.afterDestroy(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("onPause"), "on-pause",
                chain -> coordinator.afterPause(chain)));
        handles.add(hook(module, WebView.class.getDeclaredMethod("onResume"), "on-resume",
                chain -> coordinator.afterResume(chain)));
        log.event("{\"event\":\"webview_hooks_registered\",\"package\":\""
                + safeJson(packageName) + "\",\"process\":\"" + safeJson(processName) + "\"}");
        return handles.toArray(new XposedInterface.HookHandle[0]);
    }

    private static XposedInterface.HookHandle hook(XposedModule module, Method method, String id,
            XposedInterface.Hooker hooker) {
        return module.hook(method)
                .setId("dev.furihook.webview." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    private static String safeJson(String value) {
        String quoted = JSONObject.quote(value);
        return quoted.substring(1, quoted.length() - 1);
    }

    private static final class Coordinator {
        private static final int MAX_WEBVIEWS = 48;
        private static final int MAX_PENDING_TEXT_ITEMS = 168;
        private static final int MAX_TEXT_LENGTH = 900;
        private static final int MAX_PAGE_BATCH = 24;
        private static final int MAX_CACHE_ENTRIES = 128;
        private static final long POLL_INTERVAL_MS = 900L;
        private static final int MAX_INJECT_ATTEMPTS = 20;
        private static final String JS_ASSET = "furihook-web.js";

        private final XposedModule module;
        private final ModuleLog log;
        private final String packageName;
        private final String processName;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ReadingEngine engine;
        private final Map<WebView, PageState> pages = Collections.synchronizedMap(new WeakHashMap<>());
        private final Set<Class<?>> hookedClients = Collections.newSetFromMap(new WeakHashMap<>());
        private final LinkedHashMap<String, List<RubySegment>> cache = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<PageState, Work> pending = new LinkedHashMap<>();
        private final ThreadPoolExecutor worker;
        private final String script;
        private final WindowRateLimiter appliedLogs =
                new WindowRateLimiter(DetectionConfig.LOGS_PER_SECOND);
        private boolean workerDraining;
        private int pendingTextItems;

        Coordinator(XposedModule module, ModuleLog log, String packageName, String processName,
                ReadingEngine engine) throws IOException {
            this.module = module;
            this.log = log;
            this.packageName = packageName;
            this.processName = processName;
            this.engine = engine;
            this.script = readScript(module);
            this.worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1), runnable -> {
                        Thread thread = new Thread(runnable, "FuriHook-WebView-reading");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            worker.allowCoreThreadTimeOut(true);
        }

        private static String readScript(XposedModule module) throws IOException {
            String moduleApk = module.getModuleApplicationInfo().sourceDir;
            try (ZipFile apk = new ZipFile(moduleApk);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ZipEntry entry = apk.getEntry("assets/" + JS_ASSET);
                if (entry == null) throw new IOException("WebView hook script is missing from module APK");
                try (InputStream input = apk.getInputStream(entry)) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }

        Object afterNavigation(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                WebView view = (WebView) chain.getThisObject();
                PageState state = stateFor(view);
                advance(state);
                beginInjection(state, true);
            } catch (Throwable failure) {
                log.failure("webview_navigation_observation_failed", failure);
            }
            return result;
        }

        Object afterSetClient(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                Object value = chain.getArg(0);
                if (value instanceof WebViewClient) installClientHooks(value.getClass());
            } catch (Throwable failure) {
                log.failure("webview_client_hook_failed", failure);
            }
            return result;
        }

        Object afterDestroy(XposedInterface.Chain chain) throws Throwable {
            WebView view = (WebView) chain.getThisObject();
            try {
                forget(view);
            } catch (Throwable failure) {
                log.failure("webview_cleanup_failed", failure);
            }
            return chain.proceed();
        }

        Object afterPause(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                PageState state = findState((WebView) chain.getThisObject());
                if (state != null) {
                    state.polling = false;
                    state.injecting = false;
                    main.removeCallbacks(state.contentPoll);
                    main.removeCallbacks(state.injectionPoll);
                }
            } catch (Throwable failure) {
                log.failure("webview_pause_observation_failed", failure);
            }
            return result;
        }

        Object afterResume(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                PageState state = findState((WebView) chain.getThisObject());
                if (state != null) beginInjection(state, true);
            } catch (Throwable failure) {
                log.failure("webview_resume_observation_failed", failure);
            }
            return result;
        }

        private void installClientHooks(Class<?> clientClass) {
            synchronized (hookedClients) {
                for (Class<?> type = clientClass;
                     type != null && WebViewClient.class.isAssignableFrom(type);
                     type = type.getSuperclass()) {
                    if (!hookedClients.add(type)) continue;
                    installClientCallback(type, "onPageStarted",
                            new Class<?>[]{WebView.class, String.class, Bitmap.class}, true);
                    installClientCallback(type, "onPageFinished",
                            new Class<?>[]{WebView.class, String.class}, false);
                    if (Build.VERSION.SDK_INT >= 23) {
                        installClientCallback(type, "onPageCommitVisible",
                                new Class<?>[]{WebView.class, String.class}, false);
                    }
                }
            }
        }

        private void installClientCallback(Class<?> type, String name, Class<?>[] parameters,
                boolean startsNavigation) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                String id = "client-" + Integer.toHexString(type.getName().hashCode()) + "-" + name;
                module.hook(method)
                        .setId("dev.furihook.webview." + id)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                WebView view = (WebView) chain.getArg(0);
                                PageState state = stateFor(view);
                                if (startsNavigation) advance(state);
                                beginInjection(state, startsNavigation);
                            } catch (Throwable failure) {
                                log.failure("webview_page_callback_failed", failure);
                            }
                            return result;
                        });
            } catch (NoSuchMethodException ignored) {
                // The class inherits the callback; its superclass is inspected separately.
            } catch (Throwable failure) {
                log.failure("webview_page_hook_registration_failed", failure);
            }
        }

        private PageState stateFor(WebView view) {
            synchronized (pages) {
                PageState state = pages.get(view);
                if (state != null) return state;
                if (pages.size() >= MAX_WEBVIEWS) {
                    List<PageState> retired = new ArrayList<>(pages.values());
                    pages.clear();
                    for (PageState oldState : retired) forget(oldState);
                    log.event("{\"event\":\"webview_tracking_capacity_reached\",\"package\":\""
                            + safeJson(packageName) + "\"}");
                }
                state = new PageState(view);
                pages.put(view, state);
                return state;
            }
        }

        private PageState findState(WebView view) {
            synchronized (pages) {
                return pages.get(view);
            }
        }

        private void advance(PageState state) {
            state.generation++;
            state.injectionAttempts = 0;
            state.injecting = true;
            state.installed = false;
            state.inFlight = false;
            state.pullInFlight = false;
            synchronized (pending) {
                pending.remove(state);
            }
        }

        private void beginInjection(PageState state, boolean resetAttempts) {
            if (resetAttempts) state.injectionAttempts = 0;
            state.injecting = true;
            main.removeCallbacks(state.injectionPoll);
            main.postDelayed(state.injectionPoll, 200L);
            if (!state.polling) {
                state.polling = true;
                main.post(state.contentPoll);
            }
        }

        private void inject(PageState state) {
            WebView view = state.view.get();
            if (view == null) {
                forget(state);
                return;
            }
            if (!state.injecting || state.injectionAttempts >= MAX_INJECT_ATTEMPTS) {
                state.injecting = false;
                if (!state.installed) {
                    state.polling = false;
                    main.removeCallbacks(state.contentPoll);
                }
                return;
            }
            try {
                if (!view.getSettings().getJavaScriptEnabled()) {
                    state.injecting = false;
                    state.polling = false;
                    main.removeCallbacks(state.contentPoll);
                    return;
                }
            } catch (Throwable failure) {
                state.injecting = false;
                state.polling = false;
                main.removeCallbacks(state.contentPoll);
                log.failure("webview_javascript_state_unavailable", failure);
                return;
            }
            state.injectionAttempts++;
            final long generation = state.generation;
            try {
                String source = script + "\n;window.FuriHook102.install(" + generation + ");";
                view.evaluateJavascript(source, value -> {
                    if (generation != state.generation) return;
                    if ("true".equals(value)) {
                        state.installed = true;
                        pull(state);
                        if (view.getProgress() == 100) state.injecting = false;
                    }
                    if (state.injecting) {
                        main.postDelayed(state.injectionPoll, POLL_INTERVAL_MS);
                    }
                });
            } catch (Throwable failure) {
                state.injecting = false;
                if (!state.installed) {
                    state.polling = false;
                    main.removeCallbacks(state.contentPoll);
                }
                log.failure("webview_script_install_failed", failure);
            }
        }

        private void pull(PageState state) {
            if (state.pullInFlight || state.inFlight) return;
            WebView view = state.view.get();
            if (view == null) {
                forget(state);
                return;
            }
            final long generation = state.generation;
            state.pullInFlight = true;
            try {
                view.evaluateJavascript("window.FuriHook102&&window.FuriHook102.pull(" + generation + ")",
                        value -> {
                            if (generation != state.generation) return;
                            state.pullInFlight = false;
                            try {
                                String json = unwrapJavascriptString(value);
                                if (json.isEmpty()) return;
                                JSONObject batch = new JSONObject(json);
                                if (batch.optLong("generation", -1L) != generation) return;
                                JSONArray items = batch.optJSONArray("items");
                                if (items == null || items.length() == 0) return;
                                enqueue(state, generation, items);
                            } catch (Throwable failure) {
                                log.failure("webview_dom_batch_parse_failed", failure);
                            }
                        });
            } catch (Throwable failure) {
                if (generation == state.generation) state.pullInFlight = false;
                log.failure("webview_dom_pull_failed", failure);
            }
        }

        private void enqueue(PageState state, long generation, JSONArray items) throws JSONException {
            List<TextItem> textItems = new ArrayList<>();
            int count = Math.min(items.length(), MAX_PAGE_BATCH);
            for (int index = 0; index < count; index++) {
                JSONObject item = items.getJSONObject(index);
                String text = item.getString("text");
                if (text.length() > MAX_TEXT_LENGTH) text = text.substring(0, MAX_TEXT_LENGTH);
                textItems.add(new TextItem(item.getLong("id"), text));
            }
            if (textItems.isEmpty()) return;
            state.inFlight = true;
            boolean start;
            boolean rejected = false;
            synchronized (pending) {
                Work previous = pending.get(state);
                int previousCount = previous == null ? 0 : previous.items.size();
                if (pendingTextItems - previousCount + textItems.size() > MAX_PENDING_TEXT_ITEMS) {
                    rejected = true;
                    start = false;
                } else {
                    pending.put(state, new Work(generation, textItems));
                    pendingTextItems = pendingTextItems - previousCount + textItems.size();
                    start = !workerDraining;
                    if (start) workerDraining = true;
                }
            }
            if (rejected) {
                state.inFlight = false;
                drop(state, new Work(generation, textItems));
                return;
            }
            if (start) {
                try {
                    worker.execute(this::drainWork);
                } catch (Throwable failure) {
                    synchronized (pending) {
                        workerDraining = false;
                        pending.remove(state);
                    }
                    state.inFlight = false;
                    log.failure("webview_reading_queue_failed", failure);
                }
            }
        }

        private void drainWork() {
            while (true) {
                PageState state;
                Work work;
                synchronized (pending) {
                    if (pending.isEmpty()) {
                        workerDraining = false;
                        return;
                    }
                    Map.Entry<PageState, Work> entry = pending.entrySet().iterator().next();
                    state = entry.getKey();
                    work = entry.getValue();
                    pending.remove(state);
                    pendingTextItems -= work.items.size();
                }
                if (work.generation != state.generation || state.view.get() == null) continue;
                try {
                    JSONObject payload = analyze(work, state);
                    String encoded = JSONObject.quote(payload.toString());
                    main.post(() -> apply(state, work.generation, encoded));
                } catch (Throwable failure) {
                    state.inFlight = false;
                    drop(state, work);
                    log.failure("webview_reading_failed", failure);
                }
            }
        }

        private JSONObject analyze(Work work, PageState state) throws JSONException {
            JSONArray output = new JSONArray();
            for (TextItem item : work.items) {
                if (work.generation != state.generation) break;
                List<RubySegment> segments = cache.get(item.text);
                if (segments == null) {
                    segments = engine.analyze(item.text);
                    cache.put(item.text, segments);
                    if (cache.size() > MAX_CACHE_ENTRIES) {
                        cache.remove(cache.keySet().iterator().next());
                    }
                }
                JSONArray encodedSegments = new JSONArray();
                for (RubySegment segment : segments) {
                    segment.validate(item.text);
                    JSONArray encoded = new JSONArray();
                    encoded.put(segment.getStartUtf16());
                    encoded.put(segment.getEndUtf16());
                    encoded.put(segment.getRubyText());
                    encodedSegments.put(encoded);
                }
                JSONObject entry = new JSONObject();
                entry.put("id", item.id);
                entry.put("text", item.text);
                entry.put("segments", encodedSegments);
                output.put(entry);
            }
            JSONObject result = new JSONObject();
            result.put("generation", work.generation);
            result.put("items", output);
            return result;
        }

        private void apply(PageState state, long generation, String encodedPayload) {
            if (generation != state.generation) return;
            WebView view = state.view.get();
            if (view == null) {
                state.inFlight = false;
                return;
            }
            try {
                view.evaluateJavascript("window.FuriHook102&&window.FuriHook102.apply(" + encodedPayload + ")",
                        value -> {
                            if (generation != state.generation) return;
                            state.inFlight = false;
                            if (BuildConfig.DETECTION_LOGS) {
                                try {
                                    Object parsed = new JSONTokener(value).nextValue();
                                    if (parsed instanceof Number) {
                                        int count = ((Number) parsed).intValue();
                                        if (count > 0 && appliedLogs.acquire(SystemClock.elapsedRealtime())) {
                                            log.event("{\"event\":\"webview_ruby_applied\",\"package\":\""
                                                    + safeJson(packageName) + "\",\"process\":\""
                                                    + safeJson(processName) + "\",\"count\":" + count + "}");
                                        }
                                    }
                                } catch (Throwable failure) {
                                    log.failure("webview_apply_count_parse_failed", failure);
                                }
                            }
                        });
            } catch (Throwable failure) {
                state.inFlight = false;
                log.failure("webview_ruby_apply_failed", failure);
            }
        }

        private void drop(PageState state, Work work) {
            JSONArray ids = new JSONArray();
            for (TextItem item : work.items) ids.put(item.id);
            String encodedIds = JSONObject.quote(ids.toString());
            main.post(() -> {
                if (work.generation != state.generation) return;
                WebView view = state.view.get();
                if (view == null) return;
                try {
                    view.evaluateJavascript("window.FuriHook102&&window.FuriHook102.drop("
                            + encodedIds + ")", ignored -> { });
                } catch (Throwable failure) {
                    log.failure("webview_batch_cleanup_failed", failure);
                }
            });
        }

        private void forget(WebView view) {
            PageState state;
            synchronized (pages) {
                state = pages.remove(view);
            }
            if (state != null) forget(state);
        }

        private void forget(PageState state) {
            state.generation++;
            state.injecting = false;
            state.polling = false;
            state.installed = false;
            state.inFlight = false;
            state.pullInFlight = false;
            main.removeCallbacks(state.injectionPoll);
            main.removeCallbacks(state.contentPoll);
            synchronized (pending) {
                Work removed = pending.remove(state);
                if (removed != null) pendingTextItems -= removed.items.size();
            }
        }

        private static String unwrapJavascriptString(String value) throws JSONException {
            if (value == null || "null".equals(value)) return "";
            Object parsed = new JSONTokener(value).nextValue();
            return parsed instanceof String ? (String) parsed : "";
        }

        private final class PageState {
            final WeakReference<WebView> view;
            volatile long generation;
            volatile boolean injecting;
            volatile boolean polling;
            volatile boolean installed;
            volatile boolean inFlight;
            volatile boolean pullInFlight;
            int injectionAttempts;
            final Runnable injectionPoll = () -> inject(this);
            final Runnable contentPoll = new Runnable() {
                @Override public void run() {
                    if (!polling || view.get() == null) {
                        forget(PageState.this);
                        return;
                    }
                    if ((!injecting || installed) && !inFlight && !pullInFlight) pull(PageState.this);
                    main.postDelayed(this, POLL_INTERVAL_MS);
                }
            };

            PageState(WebView view) {
                this.view = new WeakReference<>(view);
            }
        }

        private static final class TextItem {
            final long id;
            final String text;
            TextItem(long id, String text) { this.id = id; this.text = text; }
        }

        private static final class Work {
            final long generation;
            final List<TextItem> items;
            Work(long generation, List<TextItem> items) {
                this.generation = generation;
                this.items = items;
            }
        }
    }
}

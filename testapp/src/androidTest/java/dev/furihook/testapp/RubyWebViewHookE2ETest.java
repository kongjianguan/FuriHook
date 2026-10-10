package dev.furihook.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class RubyWebViewHookE2ETest {
    private static final String ORIGINAL = "今日は学校。";

    @Test
    public void realWebViewGetsRubyAndKeepsDomInteractionsAndCopyText() throws Exception {
        boolean requireFuriHook = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("requireFuriHook", "false"));
        assumeTrue("设置 -e requireFuriHook true 后才运行真实 LSPosed Hook 测试",
                requireFuriHook);

        ClipboardManager clipboard = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSystemService(ClipboardManager.class);
        assertTrue("设备必须提供系统剪贴板服务", clipboard != null);
        try (ActivityScenario<WebViewRubyFixtureActivity> scenario =
                     ActivityScenario.launch(WebViewRubyFixtureActivity.class)) {
            awaitPageLoaded(scenario);
            awaitJavascript(scenario, "Boolean(window.fixtureState)");
            awaitJavascriptCondition(scenario,
                    "document.querySelectorAll('#plain ruby').length >= 2"
                            + " && document.querySelectorAll('#link ruby').length > 0");

            JSONObject initial = snapshot(scenario);
            JSONArray plainRuby = initial.getJSONArray("plainRuby");
            assertEquals(2, plainRuby.length());
            assertRuby(plainRuby, 0, "今日", "きょう");
            assertRuby(plainRuby, 1, "学校", "がっこう");
            assertEquals(ORIGINAL, initial.getString("plainBaseText"));
            assertTrue("链接中的日语也应得到实际 ruby 注音",
                    initial.getJSONArray("linkRuby").length() > 0);
            assertEquals("日本語のリンク", initial.getString("linkBaseText"));
            assertEquals("#link-result", initial.getString("linkHref"));

            tap(scenario, "link");
            awaitJavascriptCondition(scenario,
                    "document.getElementById('link-result').textContent === 'clicked'");

            tap(scenario, "dynamic-button");
            awaitJavascriptCondition(scenario,
                    "window.fixtureState.dynamicRevision === 1"
                            + " && document.querySelectorAll('#dynamic ruby').length >= 2");
            JSONObject afterDynamic = snapshot(scenario);
            JSONArray dynamicRuby = afterDynamic.getJSONArray("dynamicRuby");
            assertEquals(2, dynamicRuby.length());
            assertRuby(dynamicRuby, 0, "今日", "きょう");
            assertRuby(dynamicRuby, 1, "学校", "がっこう");
            assertEquals(ORIGINAL, afterDynamic.getString("dynamicBaseText"));

            JSONObject afterInteractions = snapshot(scenario);
            assertTrue("contenteditable 正文必须保留原始 DOM",
                    afterInteractions.getJSONArray("editableRuby").length() == 0);
            assertTrue("password 输入框不应产生 ruby 子元素",
                    afterInteractions.getJSONArray("passwordRuby").length() == 0);
            assertEquals("今日は学校。", afterInteractions.getString("editableBaseText"));
            assertEquals("password", afterInteractions.getString("passwordType"));
            assertEquals(ORIGINAL, afterInteractions.getString("passwordValue"));
            assertEquals(ORIGINAL, afterInteractions.getString("copySourceBaseText"));
            assertEquals(1, afterInteractions.getInt("fixtureRubyCount"));
            assertEquals(0, afterInteractions.getInt("fixtureNestedRubyCount"));
            assertEquals("きょう", afterInteractions.getString("fixtureOriginalReading"));

            clipboard.clearPrimaryClip();
            tap(scenario, "copy-button");
            awaitJavascriptCondition(scenario, "window.fixtureState.copyEvents > 0");
            long clipboardDeadline = SystemClock.uptimeMillis() + 5_000L;
            CharSequence copiedText = null;
            while (SystemClock.uptimeMillis() < clipboardDeadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    copiedText = clip.getItemAt(0).coerceToText(
                            InstrumentationRegistry.getInstrumentation().getTargetContext());
                    if (ORIGINAL.contentEquals(copiedText)) break;
                }
                Thread.sleep(100L);
            }
            assertTrue("复制 Ruby 正文后系统剪贴板必须只包含原文，实际为：" + copiedText,
                    ORIGINAL.contentEquals(copiedText));
            JSONObject finalState = snapshot(scenario);
            assertTrue("页面必须收到真实 copy event", finalState.getInt("copyEvents") > 0);

            JSONObject evidence = new JSONObject();
            evidence.put("test", "realWebViewGetsRubyAndKeepsDomInteractionsAndCopyText");
            evidence.put("requireFuriHook", requireFuriHook);
            evidence.put("staticRuby", plainRuby);
            evidence.put("dynamicRuby", dynamicRuby);
            evidence.put("linkRuby", finalState.getJSONArray("linkRuby"));
            evidence.put("linkClickPreserved", "clicked".equals(
                    finalState.getString("linkResult")));
            evidence.put("contentEditableExcluded", finalState
                    .getJSONArray("editableRuby").length() == 0);
            evidence.put("passwordExcluded", finalState
                    .getJSONArray("passwordRuby").length() == 0);
            evidence.put("existingRubyCount", finalState.getInt("fixtureRubyCount"));
            evidence.put("existingRubyNestedCount",
                    finalState.getInt("fixtureNestedRubyCount"));
            evidence.put("copyEventCount", finalState.getInt("copyEvents"));
            evidence.put("clipboardContainsOriginal", ORIGINAL.contentEquals(copiedText));
            evidence.put("clipboardLength", copiedText.length());
            File report = saveEvidence(scenario, evidence);
            System.out.println("FURIHOOK_WEBVIEW_E2E_REPORT=" + report.getAbsolutePath());
        }
    }

    private static JSONObject snapshot(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws Exception {
        String json = awaitJavascript(scenario,
                "(function(){"
                        + "function ruby(selector){return Array.from(document.querySelectorAll(selector+' ruby')).map(function(r){"
                        + "var c=r.cloneNode(true);c.querySelectorAll('rt,rp').forEach(function(n){n.remove()});"
                        + "return {base:c.textContent,reading:Array.from(r.querySelectorAll('rt')).map(function(n){return n.textContent}).join('')};"
                        + "})}"
                        + "function baseText(selector){var c=document.querySelector(selector).cloneNode(true);c.querySelectorAll('rt,rp').forEach(function(n){n.remove()});return c.textContent}"
                        + "var marked=document.querySelector('[data-fixture-existing]');"
                        + "return JSON.stringify({"
                        + "plainRuby:ruby('#plain'),dynamicRuby:ruby('#dynamic'),linkRuby:ruby('#link'),"
                        + "plainBaseText:baseText('#plain'),dynamicBaseText:baseText('#dynamic'),"
                        + "copySourceBaseText:baseText('#copy-source'),"
                        + "linkBaseText:baseText('#link'),linkHref:document.getElementById('link').getAttribute('href'),"
                        + "linkResult:document.getElementById('link-result').textContent,"
                        + "editableRuby:ruby('#editable'),passwordRuby:ruby('#password'),"
                        + "editableBaseText:baseText('#editable'),"
                        + "passwordType:document.getElementById('password').type,passwordValue:document.getElementById('password').value,"
                        + "fixtureRubyCount:document.querySelectorAll('[data-fixture-existing] ruby').length,"
                        + "fixtureNestedRubyCount:document.querySelectorAll('[data-fixture-existing] ruby ruby').length,"
                        + "fixtureOriginalReading:marked.querySelector('rt').textContent,"
                        + "copyEvents:window.fixtureState.copyEvents})"
                        + "})()");
        return new JSONObject(json);
    }

    private static File saveEvidence(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            JSONObject evidence) throws Exception {
        AtomicReference<File> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                result.set(activity.saveEvidence(evidence));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError("无法保存 WebView E2E 证据", failure.get());
        return result.get();
    }

    private static void tap(ActivityScenario<WebViewRubyFixtureActivity> scenario, String elementId)
            throws Exception {
        String boundsJson = awaitJavascript(scenario,
                "(function(){var e=document.getElementById('" + elementId + "');"
                        + "e.scrollIntoView({block:'center'});var r=e.getBoundingClientRect();"
                        + "return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,"
                        + "devicePixelRatio:window.devicePixelRatio})})()");
        JSONObject bounds = new JSONObject(boundsJson);
        float scale = (float) bounds.getDouble("devicePixelRatio");
        AtomicReference<WebView> view = new AtomicReference<>();
        scenario.onActivity(activity -> {
            view.set(activity.webView());
        });
        float x = (float) bounds.getDouble("x") * scale;
        float y = (float) bounds.getDouble("y") * scale;
        scenario.onActivity(activity -> {
            WebView webView = view.get();
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime,
                    MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 45,
                    MotionEvent.ACTION_UP, x, y, 0);
            try {
                webView.dispatchTouchEvent(down);
                webView.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
        });
    }

    private static String awaitJavascript(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            if (!activity.pageLoaded()) {
                failure.set(new IllegalStateException("WebView 页面尚未加载完成"));
                latch.countDown();
                return;
            }
            activity.webView().evaluateJavascript(expression, value -> {
                try {
                    Object decoded = new JSONTokener(value).nextValue();
                    result.set(String.valueOf(decoded));
                } catch (JSONException error) {
                    failure.set(error);
                }
                latch.countDown();
            });
        });
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("evaluateJavascript 超时");
        if (failure.get() != null) throw new AssertionError("evaluateJavascript 失败", failure.get());
        return result.get();
    }

    private static void awaitPageLoaded(ActivityScenario<WebViewRubyFixtureActivity> scenario)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            boolean[] loaded = new boolean[1];
            scenario.onActivity(activity -> loaded[0] = activity.pageLoaded());
            if (loaded[0]) return;
            Thread.sleep(50L);
        }
        throw new AssertionError("WebView 页面在 10 秒内没有完成加载");
    }

    private static void awaitJavascriptCondition(ActivityScenario<WebViewRubyFixtureActivity> scenario,
            String condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            String value = awaitJavascript(scenario, "Boolean(" + condition + ")");
            if (Boolean.parseBoolean(value)) return;
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(100L);
        }
        throw new AssertionError("真实 WebView 注音状态在 15 秒内未达到条件：" + condition);
    }

    private static void assertRuby(JSONArray ruby, int index, String base, String reading)
            throws JSONException {
        JSONObject segment = ruby.getJSONObject(index);
        assertEquals(base, segment.getString("base"));
        assertEquals(reading, segment.getString("reading"));
    }
}

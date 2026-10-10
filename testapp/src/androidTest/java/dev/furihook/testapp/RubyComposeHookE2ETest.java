package dev.furihook.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Window;
import android.widget.ScrollView;

import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.text.TextLayoutResult;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class RubyComposeHookE2ETest {
    private static final String RUBY_SPAN_CLASS = "dev.furihook.renderer.RubySpan";

    @Test
    public void realComposeParagraphsReceiveRubyAndKeepLinks() throws Exception {
        boolean requireFuriHook = Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("requireFuriHook", "false"));
        assumeTrue("设置 -e requireFuriHook true 后才运行真实 LSPosed Hook 测试",
                requireFuriHook);

        try (ActivityScenario<ComposeRubyFixtureActivity> scenario =
                     ActivityScenario.launch(ComposeRubyFixtureActivity.class)) {
            awaitLayout(scenario, false);
            JSONArray[] initialRuby = new JSONArray[1];
            JSONArray[] updatedRuby = new JSONArray[1];
            JSONArray[] longRuby = new JSONArray[1];
            JSONArray[] linkRanges = new JSONArray[1];
            int[] initialLines = new int[1];
            int[] longLines = new int[1];
            boolean[] simpleRuby = new boolean[1];
            boolean[] excludedRuby = new boolean[1];
            TextLayoutResult[] linkLayout = new TextLayoutResult[1];
            ComposeView[] richView = new ComposeView[1];
            scenario.onActivity(activity -> {
                Spanned initial = spanned(paragraphCharSequence(activity.richLayout()));
                assertEquals(ComposeRubyFixtureActivity.ORIGINAL, initial.toString());
                assertRuby(initial, new Expected[] {
                        new Expected(0, 2, "今日", "きょう", "きょう"),
                        new Expected(3, 5, "学校", "がっこう", "がっこう"),
                        new Expected(6, 9, "日本語", "にほんご", "にほんご"),
                        new Expected(10, 12, "勉強", "べんきょう", "べんきょう")
                });
                initialRuby[0] = rubyJsonUnchecked(initial);

                CharSequence simple = simpleCharSequence(
                        activity.findViewById(ComposeRubyFixtureActivity.ID_SIMPLE));
                assertEquals("学校", simple.toString());
                simpleRuby[0] = !rubySpans(simple).isEmpty();
                assertRuby((Spanned) simple, new Expected[] {
                        new Expected(0, 2, "学校", "がっこう", "がっこう")
                });

                TextLayoutResult longResult = activity.longLayout();
                assertNotNull(longResult);
                longLines[0] = longResult.getLineCount();
                assertTrue("真实长文本布局必须折成多行", longLines[0] > 1);
                Spanned longText = spanned(paragraphCharSequence(longResult));
                assertTrue("真实长文本布局必须包含 RubySpan", !rubySpans(longText).isEmpty());
                longRuby[0] = rubyJsonUnchecked(longText);

                String englishKana = simpleCharSequence(activity.findViewById(
                        ComposeRubyFixtureActivity.ID_ENGLISH)).toString();
                assertTrue("纯英文和纯假名不得添加 RubySpan",
                        rubySpans(simpleCharSequence(activity.findViewById(
                                ComposeRubyFixtureActivity.ID_ENGLISH))).isEmpty());
                assertTrue(englishKana.contains("English"));
                excludedRuby[0] = rubySpans(simpleCharSequence(activity.findViewById(
                        ComposeRubyFixtureActivity.ID_ENGLISH))).isEmpty();

                List<?> links = activity.richSource().getLinkAnnotations(
                        0, activity.richSource().length());
                assertEquals(1, links.size());
                Object range = links.get(0);
                Object link = invoke(range, "getItem");
                assertEquals("japanese", invoke(link, "getTag"));
                int linkStart = ((Number) invoke(range, "getStart")).intValue();
                int linkEnd = ((Number) invoke(range, "getEnd")).intValue();
                assertEquals(6, linkStart);
                assertEquals(9, linkEnd);
                linkRanges[0] = new JSONArray().put(linkStart).put(linkEnd);
                linkLayout[0] = activity.richLayout();
                richView[0] = activity.findViewById(ComposeRubyFixtureActivity.ID_RICH);
            });
            assertTrue("Compose simple text 节点必须由真实 Hook 添加 RubySpan", simpleRuby[0]);
            assertTrue("英文与假名样例必须排除 RubySpan", excludedRuby[0]);

            JSONObject pixelEvidence = captureSimpleRubyPixels(scenario);

            tapLink(scenario, richView[0], linkLayout[0],
                    ComposeRubyFixtureActivity.ORIGINAL.indexOf("日本語") + 1);
            scenario.onActivity(activity -> assertEquals("真实 Compose 链接必须响应触摸",
                    1, activity.linkClicks()));

            scenario.onActivity(activity -> activity.findViewById(
                    ComposeRubyFixtureActivity.ID_UPDATE).performClick());
            awaitLayout(scenario, true);
            scenario.onActivity(activity -> {
                Spanned updated = spanned(paragraphCharSequence(activity.richLayout()));
                assertEquals(ComposeRubyFixtureActivity.UPDATED, updated.toString());
                assertRuby(updated, new Expected[] {
                        new Expected(0, 2, "明日", "あした", "あした"),
                        new Expected(3, 6, "図書館", "としょかん", "としょかん"),
                        new Expected(7, 8, "行", "いき", "い")
                });
                updatedRuby[0] = rubyJsonUnchecked(updated);
            });

            JSONObject evidence = new JSONObject();
            evidence.put("test", "realComposeParagraphsReceiveRubyAndKeepLinks");
            evidence.put("requireFuriHook", requireFuriHook);
            evidence.put("original", ComposeRubyFixtureActivity.ORIGINAL);
            evidence.put("originalRuby", initialRuby[0]);
            evidence.put("simpleText", "学校");
            evidence.put("simpleRuby", simpleRuby[0]);
            evidence.put("simpleRubyPixelCopy", pixelEvidence);
            evidence.put("updated", ComposeRubyFixtureActivity.UPDATED);
            evidence.put("updatedRuby", updatedRuby[0]);
            evidence.put("longTextLineCount", longLines[0]);
            evidence.put("longTextRuby", longRuby[0]);
            evidence.put("englishKanaExcluded", excludedRuby[0]);
            evidence.put("linkRange", linkRanges[0]);
            evidence.put("linkClickCount", 1);
            File report = saveEvidence(scenario, evidence);
            System.out.println("FURIHOOK_COMPOSE_E2E_REPORT=" + report.getAbsolutePath());
        }
    }

    private static void awaitLayout(ActivityScenario<ComposeRubyFixtureActivity> scenario,
            boolean updated) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            boolean[] ready = new boolean[1];
            scenario.onActivity(activity -> {
                TextLayoutResult rich = activity.richLayout();
                ready[0] = rich != null && activity.longLayout() != null
                        && !rubySpans(paragraphCharSequence(rich)).isEmpty()
                        && !rubySpans(simpleCharSequence(activity.findViewById(
                                ComposeRubyFixtureActivity.ID_SIMPLE))).isEmpty()
                        && (!updated || ComposeRubyFixtureActivity.UPDATED.equals(
                                paragraphCharSequence(rich).toString())
                                && !rubySpans(paragraphCharSequence(rich)).isEmpty());
            });
            if (ready[0]) return;
            Thread.sleep(100L);
        }
        throw new AssertionError("Compose 实际段落未在 10 秒内完成布局");
    }

    private static CharSequence paragraphCharSequence(TextLayoutResult result) {
        Object multiParagraph = result.getMultiParagraph();
        List<?> infos = (List<?>) invoke(multiParagraph, "getParagraphInfoList$ui_text");
        assertTrue("Compose 实际布局必须包含段落", !infos.isEmpty());
        Object paragraph = invoke(infos.get(0), "getParagraph");
        return (CharSequence) invoke(paragraph, "getCharSequence$ui_text");
    }

    private static CharSequence simpleCharSequence(ComposeView composeView) {
        assertTrue("ComposeView 必须包含真实 AndroidComposeView", composeView.getChildCount() > 0);
        Object root = invoke(composeView.getChildAt(0), "getRoot");
        Object paragraph = findSimpleParagraph(root);
        assertNotNull("简单文本必须使用已测量的真实段落", paragraph);
        return (CharSequence) invoke(paragraph, "getCharSequence$ui_text");
    }

    private static Object findSimpleParagraph(Object layoutNode) {
        Object nodeChain = invoke(layoutNode, "getNodes$ui");
        Object node = invoke(nodeChain, "getHead$ui");
        while (node != null && node.getClass().getName().startsWith("androidx.compose.")) {
            if (node.getClass().getName().equals(
                    "androidx.compose.foundation.text.modifiers.TextStringSimpleNode")) {
                Object cache = field(node, "_layoutCache");
                Object paragraph = invoke(cache, "getParagraph$foundation");
                assertNotNull("简单文本必须使用已测量的真实段落", paragraph);
                return paragraph;
            }
            node = invoke(node, "getChild$ui");
        }
        for (Object child : (List<?>) invoke(layoutNode, "getChildren$ui")) {
            Object result = findSimpleParagraph(child);
            if (result != null) return result;
        }
        return null;
    }

    private static JSONObject captureSimpleRubyPixels(
            ActivityScenario<ComposeRubyFixtureActivity> scenario) throws Exception {
        ComposeView[] viewHolder = new ComposeView[1];
        Window[] windowHolder = new Window[1];
        Rect[] sourceHolder = new Rect[1];
        int[] bandBottomHolder = new int[1];
        scenario.onActivity(activity -> {
            ComposeView view = activity.findViewById(ComposeRubyFixtureActivity.ID_SIMPLE);
            assertTrue("简单文本 ComposeView 必须已经绘制", view.getWidth() > 0 && view.getHeight() > 0);
            Object root = invoke(view.getChildAt(0), "getRoot");
            Object paragraph = findSimpleParagraph(root);
            Spanned text = spanned((CharSequence) invoke(paragraph, "getCharSequence$ui_text"));
            assertRuby(text, new Expected[] {
                    new Expected(0, 2, "学校", "がっこう", "がっこう")
            });

            android.text.TextPaint paint = (android.text.TextPaint) invoke(
                    paragraph, "getTextPaint$ui_text");
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            float baseline = ((Number) invoke(paragraph, "getFirstBaseline")).floatValue();
            int bandBottom = (int) Math.floor(baseline + metrics.ascent);
            assertTrue("Ruby 绘制检测带必须覆盖基础字体上方", bandBottom >= 0);

            int[] location = new int[2];
            view.getLocationInWindow(location);
            sourceHolder[0] = new Rect(location[0], location[1],
                    location[0] + view.getWidth(), location[1] + view.getHeight());
            viewHolder[0] = view;
            windowHolder[0] = activity.getWindow();
            bandBottomHolder[0] = bandBottom;
        });

        ComposeView view = viewHolder[0];
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1);
        int[] copyStatus = new int[1];
        PixelCopy.request(windowHolder[0], sourceHolder[0], bitmap, status -> {
            copyStatus[0] = status;
            copied.countDown();
        }, new Handler(Looper.getMainLooper()));
        assertTrue("PixelCopy 必须在十秒内完成", copied.await(10, TimeUnit.SECONDS));
        assertEquals("PixelCopy 必须成功复制实际窗口像素", PixelCopy.SUCCESS, copyStatus[0]);

        int bandBottom = Math.min(bandBottomHolder[0], bitmap.getHeight() - 1);
        int nonWhitePixels = 0;
        for (int y = 0; y <= bandBottom; y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) != Color.WHITE) nonWhitePixels++;
            }
        }
        assertTrue("Ruby 注音必须在基础字体上方产生真实绘制像素", nonWhitePixels > 10);

        File directory = view.getContext().getExternalFilesDir(null);
        File image = new File(directory, "compose-simple-ruby-pixels.png");
        try (FileOutputStream output = new FileOutputStream(image)) {
            assertTrue("PixelCopy 位图必须保存为 PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }

        JSONObject evidence = new JSONObject();
        evidence.put("image", image.getAbsolutePath());
        evidence.put("pixelCount", nonWhitePixels);
        evidence.put("bandBottomY", bandBottom);
        evidence.put("width", view.getWidth());
        evidence.put("height", view.getHeight());
        return evidence;
    }

    private static void tapLink(ActivityScenario<ComposeRubyFixtureActivity> scenario,
            ComposeView view, TextLayoutResult layout, int offset) throws InterruptedException {
        int[] location = new int[2];
        RectF bounds = new RectF();
        view.getLocationOnScreen(location);
        Object rect = invoke(layout, "getBoundingBox", new Class<?>[] {int.class}, offset);
        bounds.set(((Number) invoke(rect, "getLeft")).floatValue(),
                ((Number) invoke(rect, "getTop")).floatValue(),
                ((Number) invoke(rect, "getRight")).floatValue(),
                ((Number) invoke(rect, "getBottom")).floatValue());
        float x = location[0] + (bounds.left + bounds.right) / 2f;
        float y = location[1] + (bounds.top + bounds.bottom) / 2f;
        long downTime = SystemClock.uptimeMillis();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ScrollView scroll = (ScrollView) view.getParent().getParent();
            int[] scrollLocation = new int[2];
            scroll.getLocationOnScreen(scrollLocation);
            scroll.smoothScrollTo(0, Math.max(0, (int) (view.getTop()
                    - scroll.getHeight() / 2f)));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        view.getLocationOnScreen(location);
        x = location[0] + (bounds.left + bounds.right) / 2f;
        y = location[1] + (bounds.top + bounds.bottom) / 2f;
        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 80,
                MotionEvent.ACTION_UP, x, y, 0);
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
            InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void assertRuby(Spanned text, Expected[] expected) {
        List<Object> spans = rubySpans(text);
        assertEquals("RubySpan 数量", expected.length, spans.size());
        for (int index = 0; index < expected.length; index++) {
            Object span = spans.get(index);
            Expected wanted = expected[index];
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            assertEquals("RubySpan 起点", wanted.start, start);
            assertEquals("RubySpan 终点", wanted.end, end);
            assertEquals("RubySpan 正文", wanted.base, text.subSequence(start, end).toString());
            assertEquals("RubySpan 读音", wanted.reading, invoke(span, "getReading"));
            assertEquals("RubySpan 注音", wanted.ruby, invoke(span, "getRubyText"));
            assertTrue("RubySpan 必须来自 FuriHook 模块类加载器",
                    span.getClass().getClassLoader() != RubyComposeHookE2ETest.class.getClassLoader());
        }
    }

    private static List<Object> rubySpans(CharSequence text) {
        if (!(text instanceof Spanned)) return Collections.emptyList();
        Spanned spanned = (Spanned) text;
        List<Object> result = new ArrayList<>();
        for (Object span : spanned.getSpans(0, text.length(), Object.class)) {
            if (span.getClass().getName().equals(RUBY_SPAN_CLASS)) result.add(span);
        }
        result.sort((left, right) -> Integer.compare(spanned.getSpanStart(left),
                spanned.getSpanStart(right)));
        return result;
    }

    private static JSONArray rubyJson(Spanned text) throws Exception {
        JSONArray result = new JSONArray();
        for (Object span : rubySpans(text)) {
            JSONObject item = new JSONObject();
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            item.put("start", start);
            item.put("end", end);
            item.put("base", text.subSequence(start, end).toString());
            item.put("reading", invoke(span, "getReading"));
            item.put("ruby", invoke(span, "getRubyText"));
            result.put(item);
        }
        return result;
    }

    private static JSONArray rubyJsonUnchecked(Spanned text) {
        try {
            return rubyJson(text);
        } catch (Exception failure) {
            throw new AssertionError("无法生成 Compose RubySpan 证据", failure);
        }
    }

    private static Spanned spanned(CharSequence text) {
        assertTrue("实际 Compose Paragraph 必须保留 Spannable RubySpan", text instanceof Spanned);
        return (Spanned) text;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取 Compose 实际布局字段 " + name, failure);
        }
    }

    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = target.getClass().getMethod(name, parameterTypes);
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法调用 Compose 实际布局方法 " + name, failure);
        }
    }

    private static File saveEvidence(ActivityScenario<ComposeRubyFixtureActivity> scenario,
            JSONObject evidence) throws Exception {
        File[] report = new File[1];
        Throwable[] failure = new Throwable[1];
        scenario.onActivity(activity -> {
            try {
                report[0] = new File(activity.getExternalFilesDir(null), "compose-ruby-e2e.json");
                try (FileOutputStream output = new FileOutputStream(report[0])) {
                    output.write(evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError("Compose E2E 证据保存失败", failure[0]);
        assertNotNull(report[0]);
        return report[0];
    }

    private static final class Expected {
        final int start;
        final int end;
        final String base;
        final String reading;
        final String ruby;

        Expected(int start, int end, String base, String reading, String ruby) {
            this.start = start;
            this.end = end;
            this.base = base;
            this.reading = reading;
            this.ruby = ruby;
        }
    }
}

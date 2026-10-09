package dev.furihook.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.text.PrecomputedText;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class RubyHookE2ETest {
    private static final String RUBY_SPAN_CLASS = "dev.furihook.renderer.RubySpan";

    @Test
    public void hookAddsCorrectRubyToRealViewsAndPreservesHostBehavior() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitRuby(scenario, MainActivity.ID_JAPANESE, 4);
            awaitRuby(scenario, MainActivity.ID_MIXED, 1);
            awaitRuby(scenario, MainActivity.ID_LONG, 1);
            awaitRuby(scenario, MainActivity.ID_RICH, 1);
            awaitRuby(scenario, MainActivity.ID_SELECTABLE, 1);
            int[] charArrayViewId = new int[1];
            int[] spannableViewId = new int[1];
            int[] immutableViewId = new int[1];
            int[] precomputedViewId = new int[1];
            PrecomputedText[] precomputedValue = new PrecomputedText[1];
            scenario.onActivity(activity -> {
                LinearLayout content = content(activity);
                TextView charArrayView = newTestView(activity, content);
                charArrayViewId[0] = charArrayView.getId();
                charArrayView.setText(new SpannableString("準備テキスト"), TextView.BufferType.SPANNABLE);
                char[] chars = "今日は学校。".toCharArray();
                charArrayView.setText(chars, 0, chars.length);

                TextView spannableView = newTestView(activity, content);
                spannableViewId[0] = spannableView.getId();
                spannableView.setText(new SpannableString("今日は学校。"),
                        TextView.BufferType.SPANNABLE);

                TextView immutableView = newTestView(activity, content);
                immutableViewId[0] = immutableView.getId();
                immutableView.setText(new SpannedString("今日は学校。"), TextView.BufferType.NORMAL);

                TextView precomputedView = newTestView(activity, content);
                precomputedViewId[0] = precomputedView.getId();
                precomputedValue[0] = PrecomputedText.create("今日は学校。",
                        precomputedView.getTextMetricsParams());
                precomputedView.setText(precomputedValue[0], TextView.BufferType.NORMAL);
                assertSame(precomputedValue[0], precomputedView.getText());
                assertNoRuby(precomputedView);
            });
            awaitRuby(scenario, charArrayViewId[0], 2);
            awaitRuby(scenario, spannableViewId[0], 2);
            awaitRuby(scenario, immutableViewId[0], 2);
            scenario.onActivity(activity -> {
                TextView japanese = activity.textView(MainActivity.ID_JAPANESE);
                assertEquals("今日は学校で日本語を勉強します。", japanese.getText().toString());
                assertRuby(japanese.getText(), new Expected[] {
                        new Expected(0, 2, "今日", "きょう", "きょう"),
                        new Expected(3, 5, "学校", "がっこう", "がっこう"),
                        new Expected(6, 9, "日本語", "にほんご", "にほんご"),
                        new Expected(10, 12, "勉強", "べんきょう", "べんきょう")
                });

                assertRubyCount(activity.textView(MainActivity.ID_MIXED).getText(), -1);
                assertRubyCount(activity.textView(MainActivity.ID_LONG).getText(), -1);
                assertTrue("长文本必须由真实 Layout 折行",
                        activity.textView(MainActivity.ID_LONG).getLayout().getLineCount() > 1);
                assertNoRuby(activity.textView(MainActivity.ID_HIRAGANA));
                assertNoRuby(activity.textView(MainActivity.ID_ENGLISH));
                assertEquals("配列から日本語", activity.textView(MainActivity.ID_CHAR_ARRAY).getText().toString());

                TextView rich = activity.textView(MainActivity.ID_RICH);
                assertEquals("日本語のリンクをタップしてください。", rich.getText().toString());
                assertEquals(1, ((Spanned) rich.getText())
                        .getSpans(0, rich.length(), ClickableSpan.class).length);
                assertTrue(rich.getText() instanceof Spannable);
                assertNotNull(rich.getLayout());
                float x = rich.getTotalPaddingLeft() + rich.getLayout().getPrimaryHorizontal(1);
                float y = rich.getTotalPaddingTop() + rich.getLayout().getLineBaseline(0);
                long downTime = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(downTime, downTime,
                        MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(downTime, downTime + 10,
                        MotionEvent.ACTION_UP, x, y, 0);
                try {
                    rich.onTouchEvent(down);
                    rich.onTouchEvent(up);
                } finally {
                    down.recycle();
                    up.recycle();
                }
                assertEquals("rich_span_clicked", rich.getTag());

                TextView selectable = activity.textView(MainActivity.ID_SELECTABLE);
                Selection.setSelection((Spannable) selectable.getText(), 0, 3);
                assertEquals(0, Selection.getSelectionStart(selectable.getText()));
                assertEquals(3, Selection.getSelectionEnd(selectable.getText()));
                assertEquals("選択可能な日本語テキストです。", selectable.getText().toString());

                assertNoRuby(activity.findViewById(MainActivity.ID_EDITABLE));
                assertNoRuby(activity.findViewById(MainActivity.ID_PASSWORD));
                EditText password = activity.findViewById(MainActivity.ID_PASSWORD);
                assertEquals("秘密の文字", password.getText().toString());
                assertRuby(activity.<TextView>findViewById(charArrayViewId[0]).getText(), japaneseExpectations());
                assertRuby(activity.<TextView>findViewById(spannableViewId[0]).getText(), japaneseExpectations());
                assertRuby(activity.<TextView>findViewById(immutableViewId[0]).getText(), japaneseExpectations());
                assertSame(precomputedValue[0], activity.<TextView>findViewById(precomputedViewId[0]).getText());

                View dynamicButton = activity.findViewById(MainActivity.ID_DYNAMIC_BUTTON);
                assertTrue(dynamicButton.performClick());
                assertTrue(dynamicButton.performClick());
            });

            awaitRuby(scenario, MainActivity.ID_DYNAMIC, 2);
            scenario.onActivity(activity -> {
                TextView dynamic = activity.textView(MainActivity.ID_DYNAMIC);
                String text = dynamic.getText().toString();
                assertEquals("动态更新第 2 次：今日は学校へ行きます。", text);
                int todayStart = text.indexOf("今日");
                int schoolStart = text.indexOf("学校");
                assertRuby(dynamic.getText(), new Expected[] {
                        new Expected(todayStart, todayStart + 2, "今日", "きょう", "きょう"),
                        new Expected(schoolStart, schoolStart + 2, "学校", "がっこう", "がっこう")
                });

                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                recycler.scrollToPosition(90);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            awaitRubyOnRecyclerRow(scenario, 90);
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        recycler.findViewHolderForAdapterPosition(90);
                assertNotNull(holder);
                TextView row = (TextView) holder.itemView;
                assertRubyCount(row.getText(), -1);

                recycler.getAdapter().onBindViewHolder(holder, 91);
                assertEquals("Item 91 contains English only.", row.getText().toString());
                assertNoRuby(row);

                recycler.getAdapter().onBindViewHolder(holder, 90);
                assertEquals("項目 90：日本語の一覧サンプル", row.getText().toString());
            });
            awaitRubyOnRecyclerRow(scenario, 90);
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        recycler.findViewHolderForAdapterPosition(90);
                assertNotNull(holder);
                assertRubyCount(((TextView) holder.itemView).getText(), -1);
            });
        }
    }

    private static void awaitRuby(ActivityScenario<MainActivity> scenario, int viewId,
            int minimumCount) throws InterruptedException {
        long deadline = android.os.SystemClock.uptimeMillis() + 10_000L;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            boolean[] ready = new boolean[1];
            scenario.onActivity(activity -> ready[0] = rubySpans(
                    activity.textView(viewId).getText()).size() >= minimumCount);
            if (ready[0]) return;
            Thread.sleep(100L);
        }
        throw new AssertionError("Hook 未在 10 秒内给 View " + viewId + " 添加 RubySpan");
    }

    private static void assertRuby(CharSequence text, Expected[] expected) {
        List<Object> spans = rubySpans(text);
        assertEquals(expected.length, spans.size());
        Spanned spanned = (Spanned) text;
        for (int index = 0; index < expected.length; index++) {
            Object span = spans.get(index);
            Expected wanted = expected[index];
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            assertEquals(wanted.start, start);
            assertEquals(wanted.end, end);
            assertEquals(wanted.base, text.subSequence(start, end).toString());
            assertEquals(wanted.reading, invokeString(span, "getReading"));
            assertEquals(wanted.ruby, invokeString(span, "getRubyText"));
        }
    }

    private static void assertRubyCount(CharSequence text, int expected) {
        int count = rubySpans(text).size();
        if (expected < 0) {
            assertTrue("目标文本应有 RubySpan", count > 0);
        } else {
            assertEquals(expected, count);
        }
    }

    private static Expected[] japaneseExpectations() {
        return new Expected[] {
                new Expected(0, 2, "今日", "きょう", "きょう"),
                new Expected(3, 5, "学校", "がっこう", "がっこう")
        };
    }

    private static void assertNoRuby(TextView view) {
        assertNotNull(view);
        assertTrue("敏感或纯英文控件不应有 RubySpan", rubySpans(view.getText()).isEmpty());
    }

    private static LinearLayout content(MainActivity activity) {
        FrameLayout container = activity.findViewById(android.R.id.content);
        return (LinearLayout) ((ScrollView) container.getChildAt(0)).getChildAt(0);
    }

    private static TextView newTestView(MainActivity activity, LinearLayout parent) {
        TextView view = new TextView(activity);
        view.setId(View.generateViewId());
        view.setTextSize(18);
        parent.addView(view);
        return view;
    }

    private static void awaitRubyOnRecyclerRow(ActivityScenario<MainActivity> scenario, int position)
            throws InterruptedException {
        long deadline = android.os.SystemClock.uptimeMillis() + 10_000L;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            boolean[] ready = new boolean[1];
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        activity.recyclerView().findViewHolderForAdapterPosition(position);
                ready[0] = holder != null && !rubySpans(((TextView) holder.itemView).getText()).isEmpty();
            });
            if (ready[0]) return;
            Thread.sleep(100L);
        }
        throw new AssertionError("Hook 未在 10 秒内给 RecyclerView 行添加 RubySpan");
    }

    private static List<Object> rubySpans(CharSequence text) {
        List<Object> matches = new ArrayList<>();
        if (!(text instanceof Spanned)) return matches;
        Spanned spanned = (Spanned) text;
        for (Object span : spanned.getSpans(0, text.length(), Object.class)) {
            if (span instanceof ReplacementSpan
                    && span.getClass().getName().equals(RUBY_SPAN_CLASS)) {
                matches.add(span);
            }
        }
        matches.sort((left, right) -> Integer.compare(
                spanned.getSpanStart(left), spanned.getSpanStart(right)));
        return matches;
    }

    private static String invokeString(Object span, String methodName) {
        try {
            Method method = span.getClass().getMethod(methodName);
            return (String) method.invoke(span);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取 RubySpan." + methodName, failure);
        }
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

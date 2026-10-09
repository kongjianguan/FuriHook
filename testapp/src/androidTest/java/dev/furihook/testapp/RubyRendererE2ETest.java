package dev.furihook.testapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.SuperscriptSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.furihook.engine.KuromojiReadingEngine;
import dev.furihook.engine.RubySegment;
import dev.furihook.renderer.RubySpan;
import dev.furihook.renderer.RubyStyle;
import dev.furihook.renderer.RubyTextRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Collections;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public final class RubyRendererE2ETest {
    private static final String SAMPLE = "今日は学校で日本語を勉強します。";
    private static final KuromojiReadingEngine ENGINE = new KuromojiReadingEngine();
    private static final RubyTextRenderer RENDERER = new RubyTextRenderer();
    private static final RubyStyle STYLE = new TestStyle();

    @Test
    public void localDictionaryProducesExactUtf16RangesAndHiraganaReadings() {
        assertSegments("今日は学校で日本語を勉強します。", new Expected[] {
                new Expected(0, 2, "きょう", "きょう"),
                new Expected(3, 5, "がっこう", "がっこう"),
                new Expected(6, 9, "にほんご", "にほんご"),
                new Expected(10, 12, "べんきょう", "べんきょう")
        });
        assertSegments("お寿司が食べたい。", new Expected[] {
                new Expected(1, 3, "すし", "すし"),
                new Expected(4, 5, "たべ", "た")
        });
        assertSegments("😀今日は学校。", new Expected[] {
                new Expected(2, 4, "きょう", "きょう"),
                new Expected(5, 7, "がっこう", "がっこう")
        });
    }

    @Test
    public void rendererAttachesRealReplacementSpansToTextViewLayout() {
        List<RubySegment> expected = ENGINE.analyze(SAMPLE);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TextView sample = activity.textView(MainActivity.ID_JAPANESE);
                String original = sample.getText().toString();
                Spannable annotated = RENDERER.annotate(sample.getText(), expected, STYLE);
                sample.setText(annotated, TextView.BufferType.SPANNABLE);

                assertEquals(original, sample.getText().toString());
                assertRubyRanges(sample.getText(), expected);
                assertNotNull(sample.getLayout());
                assertTrue(sample.getLayout().getLineCount() >= 1);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView sample = activity.textView(MainActivity.ID_JAPANESE);
                assertNotNull(sample.getLayout());
                assertTrue(sample.getMeasuredHeight() >= sample.getLayout().getHeight()
                        + sample.getCompoundPaddingTop() + sample.getCompoundPaddingBottom());
            });
        }
    }

    @Test
    public void partialCharacterStyleBoundarySkipsRubyAndPreservesHostSpan() {
        Spannable text = new SpannableString("学校");
        ForegroundColorSpan partialColor = new ForegroundColorSpan(0xffff0000);
        text.setSpan(partialColor, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        RubySegment segment = new RubySegment(0, 2, "がっこう", "がっこう");

        RENDERER.apply(text, Collections.singletonList(segment), STYLE);

        assertEquals("局部样式边界内的词语不添加 Ruby", 0, rubySpans(text).length);
        assertEquals(0, ((Spanned) text).getSpanStart(partialColor));
        assertEquals(1, ((Spanned) text).getSpanEnd(partialColor));
        assertEquals("学校", text.toString());
    }

    @Test
    public void canvasDrawingSeparatesRubyInkAndHonorsStyleAndSuperscript() {
        int[] viewIds = new int[3];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout parent = content(activity);
                TextView bold = styledTextView(activity, parent, Color.RED, true, false);
                TextView normal = styledTextView(activity, parent, Color.BLUE, false, false);
                TextView superscript = styledTextView(activity, parent, Color.BLUE, false, true);
                viewIds[0] = bold.getId();
                viewIds[1] = normal.getId();
                viewIds[2] = superscript.getId();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView bold = activity.findViewById(viewIds[0]);
                TextView normal = activity.findViewById(viewIds[1]);
                TextView superscript = activity.findViewById(viewIds[2]);
                InkBounds boldInk = drawInk(bold, Color.RED);
                InkBounds normalInk = drawInk(normal, Color.BLUE);
                InkBounds superscriptInk = drawInk(superscript, Color.BLUE);

                assertTrue("Ruby 字形必须真实绘制在原文字形区域上方",
                        boldInk.rubyPixels > 0 && boldInk.basePixels > 0
                                && boldInk.rubyBottom < boldInk.baseTop);
                assertTrue("整段 StyleSpan 粗体应增加 Canvas 实际墨迹面积",
                        boldInk.totalPixels > normalInk.totalPixels);
                assertTextMetricsContainInk(bold, boldInk);
                assertTextMetricsContainInk(normal, normalInk);
                assertTextMetricsContainInk(superscript, superscriptInk);

                int normalBaseline = normal.getExtendedPaddingTop()
                        + normal.getLayout().getLineBaseline(0);
                int superscriptBaseline = superscript.getExtendedPaddingTop()
                        + superscript.getLayout().getLineBaseline(0);
                TextPaint expectedSuperPaint = new TextPaint(superscript.getPaint());
                SuperscriptSpan[] superSpans = ((Spanned) superscript.getText())
                        .getSpans(0, superscript.length(), SuperscriptSpan.class);
                assertEquals(1, superSpans.length);
                superSpans[0].updateDrawState(expectedSuperPaint);
                int expectedShift = expectedSuperPaint.baselineShift;
                assertTrue("SuperscriptSpan 应产生真实上移", expectedShift < 0);
                int measuredShift = (superscriptInk.top - superscriptBaseline)
                        - (normalInk.top - normalBaseline);
                assertTrue("原文与 Ruby 墨迹应只应用一次 Superscript baselineShift",
                        Math.abs(measuredShift - expectedShift) <= 2);

                RubySpan span = rubySpans(bold.getText())[0];
                assertEquals(0, ((Spanned) bold.getText()).getSpanStart(span));
                assertEquals(2, ((Spanned) bold.getText()).getSpanEnd(span));
            });
        }
    }

    @Test
    public void clickableHostSpanAndSelectionRemainOnOriginalText() {
        List<RubySegment> richSegments = ENGINE.analyze("日本語のリンクをタップしてください。");
        List<RubySegment> selectionSegments = ENGINE.analyze("選択可能な日本語テキストです。");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TextView rich = activity.textView(MainActivity.ID_RICH);
                String original = rich.getText().toString();
                ClickableSpan[] hostLinks = ((Spanned) rich.getText())
                        .getSpans(0, rich.length(), ClickableSpan.class);
                assertEquals(1, hostLinks.length);

                Spannable annotated = RENDERER.annotate(rich.getText(), richSegments, STYLE);
                rich.setText(annotated, TextView.BufferType.SPANNABLE);
                assertEquals(original, rich.getText().toString());
                assertEquals(1, ((Spanned) rich.getText())
                        .getSpans(0, rich.length(), ClickableSpan.class).length);

                scrollToBottom(activity);
                TextView selectable = activity.textView(MainActivity.ID_SELECTABLE);
                Spannable selected = RENDERER.annotate(selectable.getText(),
                        selectionSegments, STYLE);
                selectable.setText(selected, TextView.BufferType.SPANNABLE);
                Selection.setSelection((Spannable) selectable.getText(), 0, 3);
                assertEquals(0, Selection.getSelectionStart(selectable.getText()));
                assertEquals(3, Selection.getSelectionEnd(selectable.getText()));
                assertEquals("選択可能な日本語テキストです。", selectable.getText().toString());

            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView rich = activity.textView(MainActivity.ID_RICH);
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
            });
        }
    }

    @Test
    public void longReadingReservesLayoutSpaceAndWrapsAroundTheRubyRange() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout content = content(activity);
                TextView narrow = new TextView(activity);
                narrow.setTextSize(20);
                narrow.setWidth(dp(activity, 132));
                narrow.setText("甲乙丙丁戊己庚辛");
                RubySegment longReading = new RubySegment(0, 2,
                        "ちょうぶんのよみかた", "ちょうぶんのよみかた");
                Spannable annotated = RENDERER.annotate(narrow.getText(),
                        Collections.singletonList(longReading), STYLE);
                narrow.setText(annotated, TextView.BufferType.SPANNABLE);
                content.addView(narrow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout content = content(activity);
                TextView narrow = (TextView) content.getChildAt(content.getChildCount() - 1);
                assertNotNull(narrow.getLayout());
                assertTrue("长文本应在真实 Layout 中换行", narrow.getLayout().getLineCount() > 1);
                assertTrue("Ruby 行高必须纳入真实 TextView 测量",
                        narrow.getMeasuredHeight() >= narrow.getLayout().getHeight());
                RubySpan span = rubySpans(narrow.getText())[0];
                assertEquals(0, ((Spanned) narrow.getText()).getSpanStart(span));
                assertEquals(2, ((Spanned) narrow.getText()).getSpanEnd(span));
                assertEquals("ちょうぶんのよみかた", span.getRubyText());
                Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
                TextPaint basePaint = new TextPaint(narrow.getPaint());
                basePaint.getFontMetricsInt(metrics);
                int baseAscent = metrics.ascent;
                int requiredWidth = span.getSize(basePaint,
                        narrow.getText(), 0, 2, metrics);
                assertTrue(requiredWidth > narrow.getPaint().measureText("甲乙"));
                assertTrue("Ruby 读音需要扩展行框顶部", metrics.ascent < baseAscent);
                int startLine = narrow.getLayout().getLineForOffset(0);
                assertEquals(startLine, narrow.getLayout().getLineForOffset(1));
                assertTrue(narrow.getLayout().getLineEnd(startLine) >= 2);
            });
        }
    }

    @Test
    public void recycledTextViewDropsRubyWhenBoundToPlainText() {
        List<RubySegment> rowSegments = ENGINE.analyze("項目 90：日本語の一覧サンプル");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                recycler.scrollToPosition(90);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                androidx.recyclerview.widget.RecyclerView recycler = activity.recyclerView();
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                        recycler.findViewHolderForAdapterPosition(90);
                assertNotNull(holder);
                TextView row = (TextView) holder.itemView;
                Spannable annotated = RENDERER.annotate(row.getText(), rowSegments, STYLE);
                row.setText(annotated, TextView.BufferType.SPANNABLE);
                assertTrue(RENDERER.hasRuby(row.getText()));

                recycler.getAdapter().onBindViewHolder(holder, 91);
                assertEquals("Item 91 contains English only.", row.getText().toString());
                assertFalse(RENDERER.hasRuby(row.getText()));

                recycler.getAdapter().onBindViewHolder(holder, 90);
                Spannable rebound = RENDERER.annotate(row.getText(), rowSegments, STYLE);
                row.setText(rebound, TextView.BufferType.SPANNABLE);
                assertTrue(RENDERER.hasRuby(row.getText()));
                assertEquals("項目 90：日本語の一覧サンプル", row.getText().toString());
            });
        }
    }

    @Test
    public void dynamicTextReplacementDropsOldRubyAndRendersNewReading() {
        List<RubySegment> firstSegments = ENGINE.analyze("动态更新第 1 次：明日は図書館へ行きます。");
        List<RubySegment> secondSegments = ENGINE.analyze("动态更新第 2 次：今日は学校へ行きます。");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View button = activity.findViewById(MainActivity.ID_DYNAMIC_BUTTON);
                TextView dynamic = activity.textView(MainActivity.ID_DYNAMIC);
                assertTrue(button.performClick());
                Spannable first = RENDERER.annotate(dynamic.getText(),
                        firstSegments, STYLE);
                dynamic.setText(first, TextView.BufferType.SPANNABLE);
                assertTrue(RENDERER.hasRuby(dynamic.getText()));

                assertTrue(button.performClick());
                assertFalse(RENDERER.hasRuby(dynamic.getText()));
                assertEquals("动态更新第 2 次：今日は学校へ行きます。", dynamic.getText().toString());
                Spannable second = RENDERER.annotate(dynamic.getText(),
                        secondSegments, STYLE);
                dynamic.setText(second, TextView.BufferType.SPANNABLE);
                assertTrue(RENDERER.hasRuby(dynamic.getText()));
                assertEquals("动态更新第 2 次：今日は学校へ行きます。", dynamic.getText().toString());
            });
        }
    }

    private void assertSegments(String source, Expected[] expected) {
        List<RubySegment> actual = ENGINE.analyze(source);
        assertEquals(source, expected.length, actual.size());
        for (int index = 0; index < expected.length; index++) {
            RubySegment segment = actual.get(index);
            Expected wanted = expected[index];
            segment.validate(source);
            assertEquals(wanted.start, segment.getStartUtf16());
            assertEquals(wanted.end, segment.getEndUtf16());
            assertEquals(wanted.reading, segment.getReading());
            assertEquals(wanted.ruby, segment.getRubyText());
        }
    }

    private void assertRubyRanges(CharSequence source, List<RubySegment> segments) {
        RubySpan[] spans = rubySpans(source);
        assertEquals(segments.size(), spans.length);
        Spanned spanned = (Spanned) source;
        for (int index = 0; index < spans.length; index++) {
            RubySegment segment = segments.get(index);
            assertEquals(segment.getStartUtf16(), spanned.getSpanStart(spans[index]));
            assertEquals(segment.getEndUtf16(), spanned.getSpanEnd(spans[index]));
            assertEquals(segment.getReading(), spans[index].getReading());
            assertEquals(segment.getRubyText(), spans[index].getRubyText());
            assertEquals(source.subSequence(segment.getStartUtf16(), segment.getEndUtf16()).toString(),
                    source.subSequence(spanned.getSpanStart(spans[index]),
                            spanned.getSpanEnd(spans[index])).toString());
        }
    }

    private static RubySpan[] rubySpans(CharSequence text) {
        assertTrue(text instanceof Spanned);
        return ((Spanned) text).getSpans(0, text.length(), RubySpan.class);
    }

    private static int dp(MainActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout content(MainActivity activity) {
        FrameLayout container = activity.findViewById(android.R.id.content);
        return (LinearLayout) ((ScrollView) container.getChildAt(0)).getChildAt(0);
    }

    private static void scrollToBottom(MainActivity activity) {
        FrameLayout container = activity.findViewById(android.R.id.content);
        ((ScrollView) container.getChildAt(0)).fullScroll(View.FOCUS_DOWN);
    }

    private static TextView styledTextView(MainActivity activity, LinearLayout parent,
            int color, boolean bold, boolean superscript) {
        SpannableString source = new SpannableString("学校");
        source.setSpan(new ForegroundColorSpan(color), 0, source.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            source.setSpan(new StyleSpan(Typeface.BOLD), 0, source.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (superscript) {
            source.setSpan(new SuperscriptSpan(), 0, source.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Spannable annotated = RENDERER.annotate(source,
                Collections.singletonList(new RubySegment(0, 2, "がっこう", "がっこう")), STYLE);
        TextView view = new TextView(activity);
        view.setId(View.generateViewId());
        view.setTextSize(32);
        view.setBackgroundColor(Color.WHITE);
        view.setIncludeFontPadding(true);
        view.setText(annotated, TextView.BufferType.SPANNABLE);
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private static InkBounds drawInk(TextView view, int targetColor) {
        assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            TextPaint viewPaintBeforeDraw = new TextPaint(view.getPaint());
            int scrollXBeforeDraw = view.getScrollX();
            int scrollYBeforeDraw = view.getScrollY();
            view.draw(new Canvas(bitmap));
            TextPaint viewPaintAfterDraw = new TextPaint(view.getPaint());
            RubySpan ruby = rubySpans(view.getText())[0];
            Spanned text = (Spanned) view.getText();
            int start = text.getSpanStart(ruby);
            int end = text.getSpanEnd(ruby);
            TextPaint basePaint = styledPaint(view, start, end);
            String baseText = text.subSequence(start, end).toString();
            Rect baseInkBounds = new Rect();
            basePaint.getTextBounds(baseText, 0, baseText.length(), baseInkBounds);
            int layoutBaseline = view.getLayout().getLineBaseline(0);
            int extendedPaddingTop = view.getExtendedPaddingTop();
            int baseline = extendedPaddingTop + layoutBaseline;
            int shiftedBaseBaseline = baseline + basePaint.baselineShift;
            int expectedBaseTop = shiftedBaseBaseline + baseInkBounds.top;
            int expectedBaseBottom = shiftedBaseBaseline + baseInkBounds.bottom;
            int total = 0;
            int top = Integer.MAX_VALUE;
            int bottom = Integer.MIN_VALUE;
            int rubyPixels = 0;
            int rubyInkBottom = Integer.MIN_VALUE;
            int basePixels = 0;
            int baseInkTop = Integer.MAX_VALUE;
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    if (!matchesColor(bitmap.getPixel(x, y), targetColor)) continue;
                    total++;
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    if (y < expectedBaseTop) {
                        rubyPixels++;
                        rubyInkBottom = Math.max(rubyInkBottom, y);
                    }
                    if (y >= expectedBaseTop && y <= expectedBaseBottom) {
                        basePixels++;
                        baseInkTop = Math.min(baseInkTop, y);
                    }
                }
            }
            String diagnostics = String.format(Locale.US,
                    "view=[%dx%d scroll=(%d,%d)->(%d,%d) paddingTop=%d layoutBaseline=%d layoutHeight=%d] "
                            + "paintBefore={%s} paintAfter={%s} basePaint={%s} baseText=%s baseBounds=%s "
                            + "basePixelsTop=%d expectedBase=[%d,%d] rubyPixels=%d rubyBottom=%d",
                    view.getWidth(), view.getHeight(), scrollXBeforeDraw, scrollYBeforeDraw,
                    view.getScrollX(), view.getScrollY(), extendedPaddingTop, layoutBaseline,
                    view.getLayout().getHeight(), paintDescription(viewPaintBeforeDraw),
                    paintDescription(viewPaintAfterDraw), paintDescription(basePaint), baseText,
                    baseInkBounds, baseInkTop, expectedBaseTop, expectedBaseBottom,
                    rubyPixels, rubyInkBottom);
            return new InkBounds(total, rubyPixels, basePixels, top, bottom,
                    rubyInkBottom, baseInkTop, baseline, diagnostics);
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean matchesColor(int pixel, int targetColor) {
        if (Color.alpha(pixel) == 0) return false;
        if (targetColor == Color.RED) {
            return Color.red(pixel) > Color.green(pixel) + 8
                    && Color.red(pixel) > Color.blue(pixel) + 8;
        }
        return Color.blue(pixel) > Color.red(pixel) + 8
                && Color.blue(pixel) > Color.green(pixel) + 8;
    }

    private static TextPaint styledPaint(TextView view, int start, int end) {
        TextPaint paint = new TextPaint(view.getPaint());
        Spanned text = (Spanned) view.getText();
        for (android.text.style.CharacterStyle style :
                text.getSpans(start, end, android.text.style.CharacterStyle.class)) {
            if (text.getSpanStart(style) <= start && text.getSpanEnd(style) >= end) {
                style.updateDrawState(paint);
            }
        }
        return paint;
    }

    private static String paintDescription(TextPaint paint) {
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        Typeface typeface = paint.getTypeface();
        return String.format(Locale.US,
                "size=%.2f typeface=%s style=%d shift=%d metrics=[%d,%d,%d,%d]",
                paint.getTextSize(), typeface, typeface == null ? -1 : typeface.getStyle(),
                paint.baselineShift, metrics.top, metrics.ascent, metrics.descent, metrics.bottom);
    }

    private static void assertTextMetricsContainInk(TextView view, InkBounds ink) {
        RubySpan span = rubySpans(view.getText())[0];
        Spanned text = (Spanned) view.getText();
        int start = text.getSpanStart(span);
        int end = text.getSpanEnd(span);
        TextPaint paint = styledPaint(view, start, end);
        Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
        TextPaint measurePaint = new TextPaint(paint);
        int baselineShift = measurePaint.baselineShift;
        measurePaint.baselineShift = 0;
        span.getSize(measurePaint, text, start, end, metrics);
        if (baselineShift < 0) {
            metrics.ascent += baselineShift;
            metrics.top += baselineShift;
        } else {
            metrics.descent += baselineShift;
            metrics.bottom += baselineShift;
        }
        int metricTop = ink.baseline + metrics.top;
        int metricBottom = ink.baseline + metrics.bottom;
        int lineTop = view.getExtendedPaddingTop() + view.getLayout().getLineTop(0);
        int lineBottom = view.getExtendedPaddingTop() + view.getLayout().getLineBottom(0);
        String measured = String.format(Locale.US,
                "ink=[%d,%d], fm=[%d,%d] (top=%d ascent=%d descent=%d bottom=%d shift=%d), line=[%d,%d]",
                ink.top, ink.bottom, metricTop, metricBottom, metrics.top, metrics.ascent,
                metrics.descent, metrics.bottom, baselineShift, lineTop, lineBottom)
                + "; " + ink.diagnostics;
        assertTrue("RubySpan FontMetricsInt 顶部必须包住真实像素; " + measured,
                ink.top >= metricTop - 2);
        assertTrue("RubySpan FontMetricsInt 底部必须包住真实像素; " + measured,
                ink.bottom <= metricBottom + 2);
        assertTrue("TextView 行框顶部必须容纳全部真实字形", ink.top >= lineTop - 2);
        assertTrue("TextView 行框底部必须容纳全部真实字形", ink.bottom <= lineBottom + 2);
    }

    private static final class InkBounds {
        final int totalPixels;
        final int rubyPixels;
        final int basePixels;
        final int top;
        final int bottom;
        final int rubyBottom;
        final int baseTop;
        final int baseline;
        final String diagnostics;

        InkBounds(int totalPixels, int rubyPixels, int basePixels, int top, int bottom,
                int rubyBottom, int baseTop, int baseline, String diagnostics) {
            this.totalPixels = totalPixels;
            this.rubyPixels = rubyPixels;
            this.basePixels = basePixels;
            this.top = top;
            this.bottom = bottom;
            this.rubyBottom = rubyBottom;
            this.baseTop = baseTop;
            this.baseline = baseline;
            this.diagnostics = diagnostics;
        }
    }

    private static final class TestStyle implements RubyStyle {
        @Override public float getTextSizeScale() { return 0.5f; }
        @Override public float getVerticalOffsetEm() { return 0f; }
        @Override public float getInterlinearSpacingEm() { return 0.1f; }
    }

    private static final class Expected {
        final int start;
        final int end;
        final String reading;
        final String ruby;

        Expected(int start, int end, String reading, String ruby) {
            this.start = start;
            this.end = end;
            this.reading = reading;
            this.ruby = ruby;
        }
    }
}

package dev.furihook.renderer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.text.Spanned;

import dev.furihook.engine.RubySegment;

/** 绘制单个词语及其上方注音的 ReplacementSpan。 */
public final class RubySpan extends ReplacementSpan {
    private final String reading;
    private final String rubyText;
    private final float textSizeScale;
    private final float verticalOffsetEm;
    private final float interlinearSpacingEm;

    public RubySpan(RubySegment segment, RubyStyle style) {
        if (segment == null || style == null) {
            throw new IllegalArgumentException("segment 和 style 不能为空");
        }
        reading = segment.getReading();
        rubyText = segment.getRubyText();
        textSizeScale = style.getTextSizeScale();
        verticalOffsetEm = style.getVerticalOffsetEm();
        interlinearSpacingEm = style.getInterlinearSpacingEm();
        if (!(textSizeScale > 0f) || !Float.isFinite(textSizeScale)
                || !Float.isFinite(verticalOffsetEm) || verticalOffsetEm < 0f
                || !Float.isFinite(interlinearSpacingEm) || interlinearSpacingEm < 0f) {
            throw new IllegalArgumentException("RubyStyle 包含无效尺寸");
        }
    }

    public String getReading() {
        return reading;
    }

    public String getRubyText() {
        return rubyText;
    }

    public float getTextSizeScale() {
        return textSizeScale;
    }

    public float getVerticalOffsetEm() {
        return verticalOffsetEm;
    }

    public float getInterlinearSpacingEm() {
        return interlinearSpacingEm;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
            Paint.FontMetricsInt fm) {
        TextPaint styledBasePaint = styledBasePaint(paint, text, start, end);
        TextPaint rubyPaint = rubyPaint(styledBasePaint);
        float baseWidth = styledBasePaint.measureText(text, start, end);
        float rubyWidth = rubyPaint.measureText(rubyText);
        Rect baseInkBounds = textBounds(styledBasePaint, text, start, end);
        Rect rubyInkBounds = textBounds(rubyPaint, rubyText);
        if (fm != null) {
            Paint.FontMetricsInt baseMetrics = styledBasePaint.getFontMetricsInt();
            float spacing = styledBasePaint.getTextSize() * interlinearSpacingEm;
            float offset = styledBasePaint.getTextSize() * verticalOffsetEm;
            float rubyBaseline = baseInkBounds.top - spacing - offset - rubyInkBounds.bottom;
            fm.top = Math.min(baseMetrics.top,
                    Math.min(baseInkBounds.top,
                            (int) Math.floor(rubyBaseline + rubyInkBounds.top)));
            fm.ascent = Math.min(baseMetrics.ascent, Math.min(baseInkBounds.top,
                    (int) Math.floor(rubyBaseline + rubyInkBounds.top)));
            fm.descent = Math.max(baseMetrics.descent, baseInkBounds.bottom);
            fm.bottom = Math.max(baseMetrics.bottom, baseInkBounds.bottom);
            fm.leading = baseMetrics.leading;
        }
        return (int) Math.ceil(Math.max(baseWidth, rubyWidth));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
            float x, int top, int y, int bottom, Paint paint) {
        TextPaint styledBasePaint = styledBasePaint(paint, text, start, end);
        TextPaint rubyPaint = rubyPaint(styledBasePaint);
        float baseWidth = styledBasePaint.measureText(text, start, end);
        float rubyWidth = rubyPaint.measureText(rubyText);
        Rect baseInkBounds = textBounds(styledBasePaint, text, start, end);
        Rect rubyInkBounds = textBounds(rubyPaint, rubyText);
        float spanWidth = Math.max(baseWidth, rubyWidth);
        float baseX = x + (spanWidth - baseWidth) / 2f;
        float rubyX = x + (spanWidth - rubyWidth) / 2f;

        if (styledBasePaint.bgColor != 0) {
            Paint backgroundPaint = new Paint(styledBasePaint);
            backgroundPaint.setColor(styledBasePaint.bgColor);
            backgroundPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(baseX, top, baseX + baseWidth, bottom, backgroundPaint);
        }
        float baseBaseline = y + styledBasePaint.baselineShift;
        canvas.drawText(text, start, end, baseX, baseBaseline, styledBasePaint);

        float spacing = styledBasePaint.getTextSize() * interlinearSpacingEm;
        float offset = styledBasePaint.getTextSize() * verticalOffsetEm;
        float rubyBaseline = baseBaseline + baseInkBounds.top - spacing - offset
                - rubyInkBounds.bottom;
        canvas.drawText(rubyText, rubyX, rubyBaseline, rubyPaint);
    }

    /** 返回注音和原文中较宽者的测量宽度，paint 应已应用该段的 metric spans。 */
    public static float computeRequiredWidth(TextPaint paint, CharSequence text,
            int start, int end, RubySegment segment, RubyStyle style) {
        if (paint == null || text == null || segment == null || style == null
                || start < 0 || end <= start || end > text.length()
                || segment.getStartUtf16() != start || segment.getEndUtf16() != end) {
            throw new IllegalArgumentException("测量参数无效");
        }
        segment.validate(text);
        RubySpan span = new RubySpan(segment, style);
        TextPaint styledBasePaint = span.styledBasePaint(paint, text, start, end);
        return Math.max(styledBasePaint.measureText(text, start, end),
                span.rubyPaint(styledBasePaint).measureText(segment.getRubyText()));
    }

    private TextPaint styledBasePaint(Paint paint, CharSequence text, int start, int end) {
        TextPaint result = copyPaint(paint);
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            for (CharacterStyle style : spanned.getSpans(start, end, CharacterStyle.class)) {
                int spanStart = spanned.getSpanStart(style);
                int spanEnd = spanned.getSpanEnd(style);
                if (!(style instanceof MetricAffectingSpan)
                        && spanStart <= start && spanEnd >= end) {
                    style.updateDrawState(result);
                }
            }
        }
        return result;
    }

    private TextPaint rubyPaint(Paint paint) {
        TextPaint result = copyPaint(paint);
        result.setTextSize(paint.getTextSize() * textSizeScale);
        return result;
    }

    private static TextPaint copyPaint(Paint paint) {
        TextPaint result = new TextPaint();
        if (paint instanceof TextPaint) {
            result.set((TextPaint) paint);
        } else {
            result.set(paint);
        }
        return result;
    }

    private static Rect textBounds(Paint paint, CharSequence text, int start, int end) {
        String value = text.subSequence(start, end).toString();
        return textBounds(paint, value);
    }

    private static Rect textBounds(Paint paint, String text) {
        Rect result = new Rect();
        paint.getTextBounds(text, 0, text.length(), result);
        return result;
    }
}

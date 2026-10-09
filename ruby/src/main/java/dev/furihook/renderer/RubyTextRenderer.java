package dev.furihook.renderer;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;

import dev.furihook.engine.RubySegment;

import java.util.ArrayList;
import java.util.List;

/** 将读音范围原地应用到 Spannable，保留宿主 CharSequence 对象和其他 spans。 */
public final class RubyTextRenderer implements RubyRenderer<Spannable> {
    private static final String RUBY_SPAN_CLASS_NAME = RubySpan.class.getName();

    @Override
    public Spannable render(CharSequence source, List<RubySegment> segments, RubyStyle style) {
        return annotate(source, segments, style);
    }

    /** 创建独立 Spannable 副本并应用 Ruby spans。 */
    public Spannable annotate(CharSequence source, List<RubySegment> segments, RubyStyle style) {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        Spannable result = new SpannableString(source);
        apply(result, segments, style);
        return result;
    }

    /** 在现有文本对象上添加 Ruby spans，不替换 CharSequence。 */
    public void apply(Spannable target, List<RubySegment> segments, RubyStyle style) {
        if (target == null || segments == null || style == null) {
            throw new IllegalArgumentException("target、segments 和 style 不能为空");
        }
        List<RubySpan> additions = new ArrayList<>();
        List<RubySegment> accepted = new ArrayList<>();
        int previousEnd = -1;
        for (RubySegment segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("segments 不能包含 null");
            }
            segment.validate(target);
            int start = segment.getStartUtf16();
            int end = segment.getEndUtf16();
            if (start < previousEnd) {
                throw new IllegalArgumentException("RubySegment 必须按原文顺序排列且不能重叠");
            }
            previousEnd = end;
            if (overlapsHostReplacementSpan(target, start, end)) {
                continue;
            }
            if (hasPartialCharacterStyleBoundary(target, start, end)) {
                continue;
            }
            accepted.add(segment);
            additions.add(new RubySpan(segment, style));
        }

        removeRuby(target);
        for (int index = 0; index < accepted.size(); index++) {
            RubySegment segment = accepted.get(index);
            target.setSpan(additions.get(index), segment.getStartUtf16(),
                    segment.getEndUtf16(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 判断文本中是否存在 FuriHook RubySpan，包括同名的其他 ClassLoader 实例。 */
    public boolean hasRuby(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        for (Object span : ((Spanned) text).getSpans(0, text.length(), Object.class)) {
            if (isRubySpan(span)) {
                return true;
            }
        }
        return false;
    }

    /** 只移除 FuriHook 自有 RubySpan，不触碰宿主 ReplacementSpan。 */
    public void removeRuby(Spannable text) {
        if (text == null) {
            throw new IllegalArgumentException("text 不能为空");
        }
        Object[] spans = text.getSpans(0, text.length(), Object.class);
        for (Object span : spans) {
            if (isRubySpan(span)) {
                text.removeSpan(span);
            }
        }
    }

    private static boolean overlapsHostReplacementSpan(Spannable text, int start, int end) {
        ReplacementSpan[] spans = text.getSpans(start, end, ReplacementSpan.class);
        for (ReplacementSpan span : spans) {
            if (!isRubySpan(span)
                    && text.getSpanStart(span) < end
                    && text.getSpanEnd(span) > start) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPartialCharacterStyleBoundary(Spannable text,
            int start, int end) {
        CharacterStyle[] spans = text.getSpans(start, end, CharacterStyle.class);
        for (CharacterStyle span : spans) {
            if (isRubySpan(span)) {
                continue;
            }
            int spanStart = text.getSpanStart(span);
            int spanEnd = text.getSpanEnd(span);
            if (spanStart <= start && spanEnd >= end) {
                continue;
            } else if (spanStart < end && spanEnd > start) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRubySpan(Object span) {
        return span != null && RUBY_SPAN_CLASS_NAME.equals(span.getClass().getName());
    }
}

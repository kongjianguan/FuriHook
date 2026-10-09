# TextView Ruby renderer

`ruby` is an Android library module that renders analyzed `RubySegment` ranges with a `ReplacementSpan`. It uses the existing core `RubySegment`, `RubyStyle`, and `RubyRenderer` contracts. Add `include(":ruby")` to `settings.gradle.kts` and `implementation(project(":ruby"))` to a consuming Android module. Its namespace is `dev.furihook.renderer`, `minSdk` is 28, `compileSdk` is 36, and Java compatibility is 17.

## Public API

`RubyTextRenderer` implements `RubyRenderer<Spannable>` and provides:

```java
Spannable annotate(CharSequence source, List<RubySegment> segments, RubyStyle style);
void apply(Spannable target, List<RubySegment> segments, RubyStyle style);
boolean hasRuby(CharSequence text);
void removeRuby(Spannable text);
```

`annotate` copies the source into a `SpannableString`; `apply` mutates and returns no replacement for the given object, so callers retain the same `TextView.getText()` buffer. Reapplying removes only this renderer's Ruby spans before adding the new result. Span identity is recognized by the `RubySpan` class name so separate class loaders can identify the module's spans. Existing host spans remain on the same text buffer.

`RubySpan(RubySegment segment, RubyStyle style)` exposes `getReading()`, `getRubyText()`, `getTextSizeScale()`, `getVerticalOffsetEm()`, and `getInterlinearSpacingEm()`. Instrumentation obtains the range through `Spanned.getSpanStart(span)` and `Spanned.getSpanEnd(span)`; those offsets are the base text's UTF-16 range. `getReading()` contains the complete lexical reading and `getRubyText()` contains the actual annotation text. `RubySpan.computeRequiredWidth(TextPaint paint, CharSequence text, int start, int end, RubySegment segment, RubyStyle style)` returns the greater of the base and annotation widths. The caller supplies a paint with the applicable metric-span state and compares this width with the view's available line width before calling `apply`.

`RubyStyle.getTextSizeScale()` controls annotation size relative to the paint used for the base text. `getVerticalOffsetEm()` shifts the annotation baseline upward by that fraction of the base paint size. `getInterlinearSpacingEm()` adds the gap above the base ascent. All values must be finite, the scale must be positive, and offset and spacing cannot be negative.

## TextView integration

For in-place rendering, the text buffer must implement `Spannable`. A caller can pass `TextView.getText()` directly to `apply` only when it is a `Spannable`; otherwise a new buffer would be required and replacing the view text would affect host-observable behavior. When a caller selects `BufferType.SPANNABLE` for the original `setText` call, asynchronous rendering can later mutate that same buffer without a second `setText` call. Character-array setters and other paths that produce non-spannable text do not receive in-place Ruby rendering.

The renderer does not invoke `TextView.setText`. Applying a span changes span/layout state and can notify `SpanWatcher`; it does not modify text characters or trigger a character `TextWatcher` change. Callers must still discard asynchronous analysis when the view's text generation or text identity has changed.

The renderer skips a candidate range when it overlaps an existing host `ReplacementSpan`, preserving the host's replacement drawing. A host `CharacterStyle` that covers the complete candidate range is retained and applied to the base drawing. A partially overlapping style whose boundary falls inside the candidate causes that Ruby segment to be skipped, preserving the original styled text. The renderer also rejects unordered or overlapping input segments and validates segment bounds and surrogate boundaries through `RubySegment.validate`.

## Layout and behavior limits

Android's `ReplacementSpan` measures and draws its covered range as a replacement run. The span width is the larger of the base and ruby text widths, and its font metrics reserve ascent for the annotation. This allows neighboring text and paragraphs to lay out around the expanded run. The platform treats the covered range as atomic for cursor movement and replacement drawing, so selection/cursor granularity inside an annotated word is reduced. A segment wider than the available line remains indivisible and may overflow rather than wrap internally.

The callback receives the paint after metric-affecting spans are applied. Android's replacement drawing path bypasses the normal per-glyph application of `CharacterStyle` spans, so full-range styles are applied explicitly; background color is drawn for the base run, and underline/strike-through paint flags and foreground/typeface settings are retained. Partial-range style boundaries cause the candidate to be skipped. Clickable spans remain in the underlying `Spannable`, but interaction behavior over a replacement range depends on the host widget's movement method and must be verified in the consuming application. Copying the underlying text retains the original characters because the span does not replace them.

Android's `ReplacementSpan` API exposes `getSize` for width and font metrics and `draw` for rendering. Its callback does not expose the `Layout` or neighboring line geometry; therefore this implementation cannot align a long annotation across multiple independently wrapped lines. The reading engine should provide word/ruby ranges that can be laid out as one segment. The caller should compare `computeRequiredWidth` with the view's available line width and omit any segment that cannot fit as one run; the renderer cannot infer a `TextView`'s width. Larger annotation width can extend beyond the base glyph width and may visually approach adjacent text.

## Verified platform references

- [ReplacementSpan API](https://developer.android.com/reference/android/text/style/ReplacementSpan)
- [LineHeightSpan API](https://developer.android.com/reference/android/text/style/LineHeightSpan)
- [AOSP TextLine implementation](https://android.googlesource.com/platform/frameworks/base/+/430fc97/core/java/android/text/TextLine.java)

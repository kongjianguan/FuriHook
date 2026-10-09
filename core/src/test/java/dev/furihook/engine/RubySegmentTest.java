package dev.furihook.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RubySegmentTest {
    @Test
    public void storesImmutableUtf16RangeAndReadingValues() {
        RubySegment segment = new RubySegment(0, 2, "ガッコウ", "がっこう");

        assertEquals(0, segment.getStartUtf16());
        assertEquals(2, segment.getEndUtf16());
        assertEquals("ガッコウ", segment.getReading());
        assertEquals("がっこう", segment.getRubyText());
    }

    @Test
    public void rejectsEmptyOrNegativeRanges() {
        assertInvalidSegment(-1, 1, "a", "a");
        assertInvalidSegment(1, 1, "a", "a");
        assertInvalidSegment(2, 1, "a", "a");
    }

    @Test
    public void rejectsMissingReadingValues() {
        assertInvalidSegment(0, 1, null, "a");
        assertInvalidSegment(0, 1, "", "a");
        assertInvalidSegment(0, 1, "a", null);
        assertInvalidSegment(0, 1, "a", "");
    }

    @Test
    public void validatesBoundsAndRejectsSurrogateSplits() {
        String text = "A" + new String(Character.toChars(0x20000)) + "B";
        new RubySegment(1, 3, "よみ", "よみ").validate(text);
        new RubySegment(0, 1, "えー", "えー").validate(text);
        new RubySegment(3, 4, "びー", "びー").validate(text);

        assertInvalidRange(new RubySegment(1, 2, "よみ", "よみ"), text);
        assertInvalidRange(new RubySegment(2, 3, "よみ", "よみ"), text);
        assertInvalidRange(new RubySegment(0, 5, "ながい", "ながい"), text);
        assertInvalidRange(new RubySegment(0, 1, "a", "a"), null);
    }

    private static void assertInvalidSegment(int start, int end, String reading, String rubyText) {
        try {
            new RubySegment(start, end, reading, rubyText);
            fail("Expected invalid RubySegment");
        } catch (IllegalArgumentException expected) {
            // 预期的输入校验错误。
        }
    }

    private static void assertInvalidRange(RubySegment segment, CharSequence text) {
        try {
            segment.validate(text);
            fail("Expected invalid source range");
        } catch (IllegalArgumentException expected) {
            // 预期的输入校验错误。
        }
    }
}

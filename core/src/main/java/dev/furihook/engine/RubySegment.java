package dev.furihook.engine;

/** 不可变的词语读音与 Ruby 注音数据。范围采用 UTF-16 半开区间。 */
public final class RubySegment {
    private final int startUtf16;
    private final int endUtf16;
    private final String reading;
    private final String rubyText;

    public RubySegment(int startUtf16, int endUtf16, String reading, String rubyText) {
        if (startUtf16 < 0 || endUtf16 <= startUtf16) {
            throw new IllegalArgumentException("原文范围必须是非空的 UTF-16 半开区间");
        }
        if (reading == null || reading.isEmpty()) {
            throw new IllegalArgumentException("reading 不能为空");
        }
        if (rubyText == null || rubyText.isEmpty()) {
            throw new IllegalArgumentException("rubyText 不能为空");
        }
        this.startUtf16 = startUtf16;
        this.endUtf16 = endUtf16;
        this.reading = reading;
        this.rubyText = rubyText;
    }

    public int getStartUtf16() {
        return startUtf16;
    }

    public int getEndUtf16() {
        return endUtf16;
    }

    /** 返回该词的完整读音，预期为平假名或待转换的片假名。 */
    public String getReading() {
        return reading;
    }

    /** 返回对应原文范围的 Ruby 注音文本。 */
    public String getRubyText() {
        return rubyText;
    }

    /** 检查范围位于输入文本内，且起止位置均未拆开代理对。 */
    public void validate(CharSequence text) {
        if (text == null || endUtf16 > text.length()) {
            throw new IllegalArgumentException("原文范围超出输入文本");
        }
        if (splitsSurrogatePair(text, startUtf16) || splitsSurrogatePair(text, endUtf16)) {
            throw new IllegalArgumentException("原文范围不能拆开 UTF-16 代理对");
        }
    }

    private static boolean splitsSurrogatePair(CharSequence text, int boundary) {
        return boundary > 0 && boundary < text.length()
                && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary));
    }
}

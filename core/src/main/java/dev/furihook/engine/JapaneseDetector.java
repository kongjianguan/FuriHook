package dev.furihook.engine;

/** 检测 Unicode 18.0 已分配的统一或兼容 Han 字符；该检测不判断文本是否属于日语。 */
public final class JapaneseDetector {
    private JapaneseDetector() {
    }

    /** 返回文本中是否至少包含一个 CJK 统一表意文字或兼容表意文字候选。 */
    public static boolean containsKanjiCandidate(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }

        for (int index = 0; index < text.length();) {
            int codePoint = Character.codePointAt(text, index);
            if (isCjkIdeographCandidate(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    /** 判断一个 Unicode code point 是否属于 Unicode 18.0 的统一或兼容 Han 字符。U+3007 不在此范围。 */
    public static boolean isCjkIdeographCandidate(int codePoint) {
        return inRange(codePoint, 0x3400, 0x4DBF)
                || inRange(codePoint, 0x4E00, 0x9FFF)
                || inRange(codePoint, 0xF900, 0xFA6D)
                || inRange(codePoint, 0xFA70, 0xFAD9)
                || inRange(codePoint, 0x20000, 0x2A6DF)
                || inRange(codePoint, 0x2A700, 0x2B73F)
                || inRange(codePoint, 0x2B740, 0x2B81E)
                || inRange(codePoint, 0x2B820, 0x2CEAD)
                || inRange(codePoint, 0x2CEB0, 0x2EBE0)
                || inRange(codePoint, 0x2EBF0, 0x2EE5D)
                || inRange(codePoint, 0x2F800, 0x2FA1D)
                || inRange(codePoint, 0x30000, 0x3134A)
                || inRange(codePoint, 0x31350, 0x323AF)
                || inRange(codePoint, 0x323B0, 0x33479);
    }

    private static boolean inRange(int value, int start, int end) {
        return value >= start && value <= end;
    }
}

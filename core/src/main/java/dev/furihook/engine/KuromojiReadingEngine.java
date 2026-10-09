package dev.furihook.engine;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Uses the local IPADIC dictionary to produce word-level Japanese readings. */
public final class KuromojiReadingEngine implements ReadingEngine {
    private volatile Tokenizer tokenizer;

    public KuromojiReadingEngine() {
    }

    @Override
    public List<RubySegment> analyze(CharSequence text) {
        if (text == null || text.length() == 0) {
            return Collections.emptyList();
        }

        String source = text instanceof String ? (String) text : text.toString();
        List<Token> tokens = tokenizer().tokenize(source);
        List<RubySegment> segments = new ArrayList<>();
        for (Token token : tokens) {
            appendSegments(source, token, segments);
        }
        if (segments.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(segments);
    }

    private Tokenizer tokenizer() {
        Tokenizer instance = tokenizer;
        if (instance == null) {
            synchronized (this) {
                instance = tokenizer;
                if (instance == null) {
                    instance = new Tokenizer();
                    tokenizer = instance;
                }
            }
        }
        return instance;
    }

    private static void appendSegments(String source, Token token, List<RubySegment> result) {
        String surface = token.getSurface();
        int sourceStart = token.getPosition();
        int sourceEnd = sourceStart + surface.length();
        if (surface.isEmpty() || sourceStart < 0 || sourceEnd > source.length()
                || !matchesAt(source, sourceStart, surface)) {
            throw new IllegalStateException("Kuromoji source range is invalid: "
                    + sourceStart + ".." + sourceEnd);
        }

        String katakanaReading = token.getReading();
        if (katakanaReading == null || katakanaReading.isEmpty()
                || "*".equals(katakanaReading) || !isKatakanaReading(katakanaReading)) {
            return;
        }

        List<TokenGroup> groups = splitGroups(surface);
        if (groups == null) {
            return;
        }
        boolean containsKanji = false;
        List<TokenGroup> kanaGroups = new ArrayList<>();
        for (TokenGroup group : groups) {
            if (group.kanji) {
                containsKanji = true;
            } else {
                group.kanaCodePoints = toHiraganaCodePoints(surface, group.start, group.end);
                kanaGroups.add(group);
            }
        }
        if (!containsKanji) {
            return;
        }

        int[] readingCodePoints = toHiraganaCodePoints(katakanaReading, 0, katakanaReading.length());
        int[] alignment = findUniqueKanaAlignment(groups, kanaGroups, readingCodePoints);
        if (alignment == null) {
            return;
        }

        int[] readingOffsets = codePointOffsets(katakanaReading);
        String fullReading = toHiragana(katakanaReading);
        List<RubySegment> tokenSegments = new ArrayList<>();
        int readingCursor = 0;
        int kanaIndex = 0;
        for (TokenGroup group : groups) {
            if (!group.kanji) {
                int matchStart = alignment[kanaIndex];
                readingCursor = matchStart + group.kanaCodePoints.length;
                kanaIndex++;
                continue;
            }

            int readingEnd = readingCodePoints.length;
            if (kanaIndex < kanaGroups.size()) {
                readingEnd = alignment[kanaIndex];
            }
            if (readingCursor < readingEnd) {
                String rubyText = katakanaReading.substring(
                        readingOffsets[readingCursor], readingOffsets[readingEnd]);
                rubyText = toHiragana(rubyText);
                if (rubyText == null || rubyText.isEmpty()) {
                    return;
                }
                RubySegment segment = new RubySegment(
                        sourceStart + group.start,
                        sourceStart + group.end,
                        fullReading,
                        rubyText);
                segment.validate(source);
                tokenSegments.add(segment);
            } else {
                return;
            }
        }
        result.addAll(tokenSegments);
    }

    private static List<TokenGroup> splitGroups(String surface) {
        List<TokenGroup> groups = new ArrayList<>();
        int groupStart = 0;
        boolean groupIsKanji = false;
        boolean hasGroup = false;
        for (int index = 0; index < surface.length();) {
            int codePoint = surface.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            boolean kanji = JapaneseDetector.isCjkIdeographCandidate(codePoint);
            if (!kanji && !isKana(codePoint)) {
                return null;
            }
            if (!hasGroup) {
                groupStart = index;
                groupIsKanji = kanji;
                hasGroup = true;
            } else if (kanji != groupIsKanji) {
                groups.add(new TokenGroup(groupStart, index, groupIsKanji));
                groupStart = index;
                groupIsKanji = kanji;
            }
            index = next;
        }
        if (hasGroup) {
            groups.add(new TokenGroup(groupStart, surface.length(), groupIsKanji));
        }
        return groups;
    }

    private static int[] findUniqueKanaAlignment(
            List<TokenGroup> groups,
            List<TokenGroup> kanaGroups,
            int[] reading) {
        if (kanaGroups.isEmpty()) {
            return new int[0];
        }
        AlignmentSearch search = new AlignmentSearch(groups, kanaGroups, reading);
        search.find(0, 0);
        return search.solutions == 1 ? search.uniqueAlignment : null;
    }

    private static final class AlignmentSearch {
        private final List<TokenGroup> groups;
        private final List<TokenGroup> kanaGroups;
        private final int[] reading;
        private final int[] candidateAlignment;
        private int solutions;
        private int[] uniqueAlignment;

        private AlignmentSearch(
                List<TokenGroup> groups,
                List<TokenGroup> kanaGroups,
                int[] reading) {
            this.groups = groups;
            this.kanaGroups = kanaGroups;
            this.reading = reading;
            this.candidateAlignment = new int[kanaGroups.size()];
        }

        private void find(int groupIndex, int minimumStart) {
            if (solutions > 1) {
                return;
            }
            if (groupIndex == kanaGroups.size()) {
                solutions++;
                if (solutions == 1) {
                    uniqueAlignment = candidateAlignment.clone();
                }
                return;
            }

            TokenGroup kana = kanaGroups.get(groupIndex);
            boolean leading = groups.get(0) == kana;
            boolean trailing = groups.get(groups.size() - 1) == kana;
            int first = leading ? 0 : minimumStart + 1;
            int last = reading.length - kana.kanaCodePoints.length;
            if (leading) {
                last = 0;
            }
            if (trailing) {
                first = Math.max(first, reading.length - kana.kanaCodePoints.length);
                last = reading.length - kana.kanaCodePoints.length;
            }
            for (int start = first; start <= last; start++) {
                if (matches(reading, start, kana.kanaCodePoints)) {
                    candidateAlignment[groupIndex] = start;
                    find(groupIndex + 1, start + kana.kanaCodePoints.length);
                    if (solutions > 1) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean matches(int[] text, int start, int[] pattern) {
        if (start < 0 || start + pattern.length > text.length) {
            return false;
        }
        for (int index = 0; index < pattern.length; index++) {
            if (text[start + index] != pattern[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAt(String source, int start, String surface) {
        if (start < 0 || start + surface.length() > source.length()) {
            return false;
        }
        for (int index = 0; index < surface.length(); index++) {
            if (source.charAt(start + index) != surface.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static int[] toHiraganaCodePoints(String text, int start, int end) {
        int[] codePoints = new int[text.codePointCount(start, end)];
        int outputIndex = 0;
        for (int index = start; index < end;) {
            int codePoint = text.codePointAt(index);
            if (!isKana(codePoint)) {
                return null;
            }
            codePoints[outputIndex++] = toHiraganaCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return codePoints;
    }

    private static String toHiragana(String katakana) {
        StringBuilder result = new StringBuilder(katakana.length());
        for (int index = 0; index < katakana.length();) {
            int codePoint = katakana.codePointAt(index);
            if (!isKatakanaReadingCodePoint(codePoint)) {
                return null;
            }
            result.appendCodePoint(toHiraganaCodePoint(codePoint));
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int[] codePointOffsets(String text) {
        int count = text.codePointCount(0, text.length());
        int[] offsets = new int[count + 1];
        int codePointIndex = 0;
        for (int charIndex = 0; charIndex < text.length();) {
            offsets[codePointIndex++] = charIndex;
            charIndex += Character.charCount(text.codePointAt(charIndex));
        }
        offsets[count] = text.length();
        return offsets;
    }

    private static int toHiraganaCodePoint(int codePoint) {
        if (codePoint >= 0x30A1 && codePoint <= 0x30F6) {
            return codePoint - 0x60;
        }
        return codePoint;
    }

    private static boolean isKatakanaReading(String text) {
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (!isKatakanaReadingCodePoint(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isKatakanaReadingCodePoint(int codePoint) {
        return isKatakana(codePoint) || codePoint == 0x30FC;
    }

    private static boolean isKana(int codePoint) {
        return (codePoint >= 0x3041 && codePoint <= 0x309F)
                || isKatakanaReadingCodePoint(codePoint);
    }

    private static boolean isKatakana(int codePoint) {
        return (codePoint >= 0x30A1 && codePoint <= 0x30FA)
                || (codePoint >= 0x30FD && codePoint <= 0x30FF);
    }

    private static final class TokenGroup {
        private final int start;
        private final int end;
        private final boolean kanji;
        private int[] kanaCodePoints;

        private TokenGroup(int start, int end, boolean kanji) {
            this.start = start;
            this.end = end;
            this.kanji = kanji;
        }
    }
}

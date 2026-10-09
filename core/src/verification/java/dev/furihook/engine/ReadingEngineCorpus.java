package dev.furihook.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reproducible JVM corpus check for the real local dictionary dependency. */
public final class ReadingEngineCorpus {
    private ReadingEngineCorpus() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one absolute path for the TSV report");
        }
        StringBuilder report = new StringBuilder();
        emit(report, "source\tstartUtf16\tendUtf16\treading\trubyText\tstatus");
        ReadingEngine engine = new KuromojiReadingEngine();
        verify(report, engine, "今日は学校で日本語を勉強します。", new Expected[] {
                new Expected(0, 2, "きょう", "きょう"),
                new Expected(3, 5, "がっこう", "がっこう"),
                new Expected(6, 9, "にほんご", "にほんご"),
                new Expected(10, 12, "べんきょう", "べんきょう")
        });
        verify(report, engine, "お寿司が食べたい。", new Expected[] {
                new Expected(1, 3, "すし", "すし"),
                new Expected(4, 5, "たべ", "た")
        });
        verify(report, engine, "😀今日は学校。", new Expected[] {
                new Expected(2, 4, "きょう", "きょう"),
                new Expected(5, 7, "がっこう", "がっこう")
        });
        verify(report, engine, "食べます", new Expected[] {
                new Expected(0, 1, "たべ", "た")
        });
        verify(report, engine, "お祝い", new Expected[] {
                new Expected(1, 2, "おいわい", "いわ")
        });
        verify(report, engine, "お送り", new Expected[] {
                new Expected(1, 2, "おおくり", "おく")
        });
        verify(report, engine, "読み物", new Expected[] {
                new Expected(0, 1, "よみもの", "よ"),
                new Expected(2, 3, "よみもの", "もの")
        });
        verify(report, engine, "申し込む", new Expected[] {
                new Expected(0, 1, "もうしこむ", "もう"),
                new Expected(2, 3, "もうしこむ", "こ")
        });
        verify(report, engine, "取り扱い", new Expected[] {
                new Expected(0, 1, "とりあつかい", "と"),
                new Expected(2, 3, "とりあつかい", "あつか")
        });
        verify(report, engine, "今日", new Expected[] {
                new Expected(0, 2, "きょう", "きょう")
        });
        verify(report, engine, "大人", new Expected[] {
                new Expected(0, 2, "おとな", "おとな")
        });
        verify(report, engine, "明日", new Expected[] {
                new Expected(0, 2, "あした", "あした")
        });
        verify(report, engine, "𠮷野家", new Expected[] {
                new Expected(2, 4, "のや", "のや")
        });
        verifyEmpty(report, engine, "𠮷");
        verifyEmpty(report, engine, "ひらがなだけ");
        verifyEmpty(report, engine, "This is Japanese");
        emit(report, "Kuromoji reading corpus passed.");
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private static void verifyEmpty(StringBuilder report, ReadingEngine engine, String source) {
        if (!engine.analyze(source).isEmpty()) {
            throw new IllegalStateException("Unexpected ruby segments for: " + source);
        }
        emit(report, source + "\t\t\t\t\tno-readings");
    }

    private static void verify(
            StringBuilder report, ReadingEngine engine, String source, Expected[] expected) {
        List<RubySegment> actual = engine.analyze(source);
        if (actual.size() != expected.length) {
            throw new IllegalStateException("Unexpected segment count for: " + source + " -> " + actual.size());
        }
        for (int index = 0; index < expected.length; index++) {
            Expected wanted = expected[index];
            RubySegment segment = actual.get(index);
            segment.validate(source);
            if (segment.getStartUtf16() != wanted.start
                    || segment.getEndUtf16() != wanted.end
                    || !segment.getReading().equals(wanted.reading)
                    || !segment.getRubyText().equals(wanted.rubyText)) {
                throw new IllegalStateException("Unexpected segment for " + source + " at " + index
                        + ": " + segment.getStartUtf16() + ".." + segment.getEndUtf16()
                        + " reading=" + segment.getReading() + " ruby=" + segment.getRubyText());
            }
            emit(report, source + "\t" + segment.getStartUtf16() + "\t"
                    + segment.getEndUtf16() + "\t" + segment.getReading() + "\t"
                    + segment.getRubyText() + "\tsegment");
        }
    }

    private static void emit(StringBuilder report, String line) {
        report.append(line).append('\n');
        System.out.println(line);
    }

    private static final class Expected {
        private final int start;
        private final int end;
        private final String reading;
        private final String rubyText;

        private Expected(int start, int end, String reading, String rubyText) {
            this.start = start;
            this.end = end;
            this.reading = reading;
            this.rubyText = rubyText;
        }
    }
}

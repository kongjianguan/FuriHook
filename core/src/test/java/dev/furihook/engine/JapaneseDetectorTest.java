package dev.furihook.engine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JapaneseDetectorTest {
    @Test
    public void handlesNullEmptyAndKanaOnlyText() {
        assertFalse(JapaneseDetector.containsKanjiCandidate(null));
        assertFalse(JapaneseDetector.containsKanjiCandidate(""));
        assertFalse(JapaneseDetector.containsKanjiCandidate("にほんご"));
    }

    @Test
    public void recognizesBoundariesOfUnicode18HanRanges() {
        int[][] ranges = {
                {0x3400, 0x4DBF}, {0x4E00, 0x9FFF}, {0xF900, 0xFA6D},
                {0xFA70, 0xFAD9}, {0x20000, 0x2A6DF}, {0x2A700, 0x2B73F},
                {0x2B740, 0x2B81E}, {0x2B820, 0x2CEAD}, {0x2CEB0, 0x2EBE0},
                {0x2EBF0, 0x2EE5D}, {0x2F800, 0x2FA1D}, {0x30000, 0x3134A},
                {0x31350, 0x323AF}, {0x323B0, 0x33479}
        };

        for (int[] range : ranges) {
            assertTrue(JapaneseDetector.isCjkIdeographCandidate(range[0]));
            assertTrue(JapaneseDetector.isCjkIdeographCandidate(range[1]));
        }
        int[] unassigned = {
                0x33FF, 0x4DC0, 0x2A6E0, 0xFA6E, 0xFA6F, 0xFADA,
                0xFADB, 0xFAFF, 0x2B81F, 0x2CEAE, 0x2EBE1, 0x2EE5E,
                0x2FA1E, 0x2FA1F, 0x3134B, 0x3347A, 0x3007
        };
        for (int codePoint : unassigned) {
            assertFalse("Unexpected Han candidate U+" + Integer.toHexString(codePoint),
                    JapaneseDetector.isCjkIdeographCandidate(codePoint));
        }
    }

    @Test
    public void walksSupplementaryCharactersAsCodePoints() {
        String supplementaryHan = new String(Character.toChars(0x20000));
        assertTrue(JapaneseDetector.containsKanjiCandidate("かな" + supplementaryHan + "かな"));
        assertFalse(JapaneseDetector.containsKanjiCandidate("かな\uD840"));
    }

    @Test
    public void candidateDetectionDoesNotClaimJapaneseLanguage() {
        assertTrue(JapaneseDetector.containsKanjiCandidate("漢字"));
        assertFalse(JapaneseDetector.containsKanjiCandidate("かなカナ"));
    }
}

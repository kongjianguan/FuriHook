# Reading engine

## Failure cases to guard against

- Token offsets may be interpreted as Unicode code points instead of Java UTF-16 indices, shifting every span after a supplementary character.
- A tokenizer can emit `*`, an empty reading, or an unknown-word reading; displaying those values would create false ruby.
- Reading an inflected surface as a whole can repeat its okurigana above kanji; stripping an unmatched kana suffix can also invent a reading.
- A surface can contain multiple kanji runs separated by kana. Assigning the whole token reading to only one run has no reliable alignment.
- A multi-kanji token may be a jukujikun whose reading cannot be split character by character.
- Converting katakana by changing code units can corrupt supplementary characters or non-kana marks.
- Reconstructing the source span from the token surface can drift if the tokenizer's position semantics are misunderstood.
- Dictionary data and implementation can have separate license notices; omitting the embedded IPADIC notice can violate redistribution terms.
- Dictionary initialization and analysis can allocate or block; callers must reuse an engine and run analysis off the UI thread.

## Engine choice and verified API

The core uses `com.atilika.kuromoji:kuromoji-ipadic:0.9.0`. The [official Kuromoji README](https://github.com/atilika/kuromoji) documents this Maven coordinate and the `com.atilika.kuromoji.ipadic.Tokenizer` / `Token` API. The published token API exposes `getPosition()`, `getSurface()`, and `getReading()`; `getReading()` returns katakana. Kuromoji's tokenizer source passes Java `String` indices through `substring`, `indexOf`, and `length`, so token positions and `surface.length()` form UTF-16 half-open ranges.

The released IPADIC JAR is 13,343,016 bytes and expands to 33,467,827 bytes. It includes the IPADIC binary dictionary. Kuromoji code is Apache-2.0; the artifact also embeds `META-INF/NOTICE.md` for IPADIC/ICOT data and its no-warranty terms. Keep the dependency's license and notice with redistributed builds. IPADIC is an older dictionary and does not cover all current names: for example, the corpus skips unknown `𠮷` while the following `野家` receives its dictionary reading. The engine does not claim to solve personal or proper-name readings.

Sudachi's [official Java project](https://github.com/WorksApplications/Sudachi) provides reading output, but requires a separately distributed binary system dictionary; its official documentation requires a matching engine/dictionary format and warns that the 0.8 series can change incompatibly between patch versions. MeCab's [official project](https://github.com/taku910/mecab) is a native C++ analyzer and would require an Android JNI/NDK integration and dictionary packaging. Kuromoji fits the existing Java-only core and Maven dependency setup without a new Android-specific module.

## Ruby alignment rules

The engine annotates whole all-kanji tokens as one segment, preserving word readings such as jukujikun. For tokens containing multiple kanji runs separated by kana, it searches the token reading for ordered matches of those kana runs and emits ranges only when exactly one alignment exists. Leading kana must match at the beginning of the reading. Every kanji run must receive at least one reading code point; if any run is empty, the entire token is skipped. Non-kana material around kanji, missing readings, unmatched okurigana, and ambiguous alignments are skipped. Kuromoji source ranges that do not match their token surface fail immediately with `IllegalStateException`.

Katakana readings are converted to hiragana by Unicode code point. The `reading` field stores the full token reading in hiragana; `rubyText` stores the part aligned to the annotated kanji run. All offsets remain UTF-16 half-open ranges. Constructing `KuromojiReadingEngine` is lightweight; its first `analyze` call loads the dictionary. Call that first analysis from a background worker so dictionary loading never blocks the UI thread. Reuse the same engine instance for later analyses.

## Repeatable verification

Run `./gradlew :core:verifyReadingCorpus` to execute the local JVM corpus against the real Kuromoji artifact. It asserts exact UTF-16 ranges, full readings, and ruby text for ordinary words, inflected okurigana, multiple kana/kanji runs, jukujikun (`今日`, `大人`, `明日`), unknown characters, kana-only and English text, and a supplementary-plane prefix. It prints tab-separated rows and writes the same report to `core/build/reports/reading-corpus.tsv`. This is a runnable corpus check, not a unit-test suite. Android instrumentation should separately verify the engine on API 28 and the chosen current emulator image.

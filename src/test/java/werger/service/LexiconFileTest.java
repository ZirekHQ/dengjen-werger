package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationSay;

class LexiconFileTest {
    private static final String LEXICON = """
            written\tsay\tnote
            AB\tYekîtiya Ewropayê\tthe EU
            silav\t/sɪlˈav/
            """;

    static PronunciationFix fix(String written, PronunciationSay say, Optional<String> note) {
        return new PronunciationFix(
                UUID.randomUUID(), "kmr", written, say, note, UUID.randomUUID(), Instant.parse("2026-11-11T12:00:00Z"));
    }

    private static PronunciationFix respelling(String written, String say) {
        return fix(written, new PronunciationSay.Respelling(say), Optional.empty());
    }

    @Test
    void containsMatchesOnlyTheExactRow() {
        assertThat(LexiconFile.contains(
                        LEXICON, fix("silav", new PronunciationSay.Phonemes("sɪlˈav"), Optional.empty())))
                .isTrue();
        assertThat(LexiconFile.contains(LEXICON, respelling("silav", "Silav"))).isFalse();
        assertThat(LexiconFile.contains(LEXICON, respelling("AB", "Yekîtiya Ewropayê")))
                .isFalse();
    }

    @Test
    void theHeaderIsNeverTakenForARow() {
        assertThat(LexiconFile.contains("written\tsay\tnote\n", respelling("written", "say\tnote")))
                .isFalse();
    }

    @Test
    void aNewWrittenFormIsAddedAfterTheLastRow() {
        assertThat(LexiconFile.apply(LEXICON, List.of(respelling("NY", "Neteweyên Yekbûyî"))))
                .isEqualTo(LEXICON + "NY\tNeteweyên Yekbûyî\n");
    }

    @Test
    void aFixForAnExistingWrittenFormReplacesThatRowInPlace() {
        assertThat(LexiconFile.apply(LEXICON, List.of(respelling("AB", "Ewropa"))))
                .isEqualTo("""
                        written\tsay\tnote
                        AB\tEwropa
                        silav\t/sɪlˈav/
                        """);
    }

    @Test
    void writtenFormsAreMatchedWithTheirCase() {
        assertThat(LexiconFile.apply(LEXICON, List.of(respelling("ab", "av")))).isEqualTo(LEXICON + "ab\tav\n");
    }

    @Test
    void anEmptyFileGetsTheHeaderFirst() {
        assertThat(LexiconFile.apply("", List.of(respelling("AB", "Ewropa"))))
                .isEqualTo("written\tsay\tnote\nAB\tEwropa\n");
    }

    @Test
    void windowsLineEndingsAreReadAndTheFileIsWrittenWithNewlines() {
        assertThat(LexiconFile.apply("written\tsay\tnote\r\nAB\tx\r\n", List.of(respelling("AB", "Ewropa"))))
                .isEqualTo("written\tsay\tnote\nAB\tEwropa\n");
    }
}

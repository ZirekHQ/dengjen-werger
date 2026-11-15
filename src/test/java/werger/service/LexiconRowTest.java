package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationSay;
import werger.ports.Result;

class LexiconRowTest {
    private static PronunciationFix fix(String written, PronunciationSay say, Optional<String> note) {
        return new PronunciationFix(UUID.randomUUID(), "kmr", written, say, note, UUID.randomUUID(), Instant.EPOCH);
    }

    @Test
    void aWordIsTrimmedAndKeptInItsCase() {
        assertThat(LexiconRow.parseWritten("  AB ")).isEqualTo(Result.ok("AB"));
        assertThat(LexiconRow.parseWritten("Kurmancî")).isEqualTo(Result.ok("Kurmancî"));
    }

    @Test
    void aDecomposedLetterIsComposedSoTheSameWordIsOneKey() {
        assertThat(LexiconRow.parseWritten("kurmancî")).isEqualTo(Result.ok("kurmancî"));
    }

    @Test
    void theWrittenFormMustBeOneWordOfLetters() {
        assertThat(LexiconRow.parseWritten(" ")).isEqualTo(Result.err(new LexiconRowError.EmptyWritten()));
        assertThat(LexiconRow.parseWritten("two words"))
                .isEqualTo(Result.err(new LexiconRowError.NotOneWord("two words")));
        assertThat(LexiconRow.parseWritten("3D")).isEqualTo(Result.err(new LexiconRowError.NotOneWord("3D")));
        assertThat(LexiconRow.parseWritten("e.g")).isEqualTo(Result.err(new LexiconRowError.NotOneWord("e.g")));
    }

    @Test
    void aRespellingKeepsKurmanjiLettersAndCollapsesSpaces() {
        assertThat(LexiconRow.parseSay("  Yekîtiya   Ewropayê "))
                .isEqualTo(Result.ok(new PronunciationSay.Respelling("Yekîtiya Ewropayê")));
        assertThat(LexiconRow.parseSay("Çarşem-ê'Ş"))
                .isEqualTo(Result.ok(new PronunciationSay.Respelling("Çarşem-ê'Ş")));
    }

    @Test
    void aRespellingNamesEachForbiddenCharacterOnce() {
        assertThat(LexiconRow.parseSay("silav 2, silav 3."))
                .isEqualTo(Result.err(new LexiconRowError.ForbiddenCharacters("2,3.")));
        assertThat(LexiconRow.parseSay("θ\tx")).isEqualTo(Result.err(new LexiconRowError.ForbiddenCharacters("θ\t")));
    }

    @Test
    void phonemesAreWrittenBetweenSlashes() {
        assertThat(LexiconRow.parseSay(" / sɪlˈav / ")).isEqualTo(Result.ok(new PronunciationSay.Phonemes("sɪlˈav")));
        assertThat(LexiconRow.parseSay("/sɪl  ˈav/")).isEqualTo(Result.ok(new PronunciationSay.Phonemes("sɪl ˈav")));
    }

    @Test
    void phonemesNeedBothSlashesAndSomethingBetweenThem() {
        assertThat(LexiconRow.parseSay("")).isEqualTo(Result.err(new LexiconRowError.EmptySay()));
        assertThat(LexiconRow.parseSay("/")).isEqualTo(Result.err(new LexiconRowError.UnclosedPhonemes()));
        assertThat(LexiconRow.parseSay("/sɪl")).isEqualTo(Result.err(new LexiconRowError.UnclosedPhonemes()));
        assertThat(LexiconRow.parseSay("sɪl/")).isEqualTo(Result.err(new LexiconRowError.UnclosedPhonemes()));
        assertThat(LexiconRow.parseSay("/ /")).isEqualTo(Result.err(new LexiconRowError.EmptyPhonemes()));
    }

    @Test
    void phonemesCannotHoldATabSlashOrLineBreak() {
        assertThat(LexiconRow.parseSay("/a\tb/c\nd/"))
                .isEqualTo(Result.err(new LexiconRowError.ForbiddenCharacters("\t/\n")));
    }

    @Test
    void aRowHasTheNoteOnlyWhenThereIsOne() {
        assertThat(LexiconRow.render(fix("AB", new PronunciationSay.Respelling("Yekîtiya Ewropayê"), Optional.empty())))
                .isEqualTo("AB\tYekîtiya Ewropayê");
        assertThat(LexiconRow.render(fix("silav", new PronunciationSay.Phonemes("sɪlˈav"), Optional.of("  "))))
                .isEqualTo("silav\t/sɪlˈav/");
    }

    @Test
    void aNoteCannotBreakTheRowWithATabOrNewline() {
        assertThat(LexiconRow.render(fix(
                        "AB",
                        new PronunciationSay.Respelling("Yekîtiya Ewropayê"),
                        Optional.of(" the\tEuropean\nUnion "))))
                .isEqualTo("AB\tYekîtiya Ewropayê\tthe European Union");
    }

    @Test
    void aNoteCannotBreakTheRowWithAUnicodeLineSeparator() {
        assertThat(LexiconRow.render(fix(
                        "AB",
                        new PronunciationSay.Respelling("Yekîtiya Ewropayê"),
                        Optional.of("a\u2028b\u0085c\u2029d"))))
                .isEqualTo("AB\tYekîtiya Ewropayê\ta b c d");
    }
}

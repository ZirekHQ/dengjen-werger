package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import werger.domain.Speech;
import werger.ports.Result;
import werger.ports.SynthesisError;

class ListeningServiceTest {
    private static final Speech SPEECH = new Speech(new byte[] {1}, 8000, 1, 1);

    private final List<String> spoken = new ArrayList<>();
    private Result<SynthesisError, Speech> answer = Result.ok(SPEECH);
    private final ListeningService listening = new ListeningService(text -> {
        spoken.add(text);
        return answer;
    });

    @Test
    void kurmanjiHasTestSentencesWithoutCommentsOrBlankLines() {
        List<String> sentences = listening.sentences("kmr");

        assertThat(sentences)
                .isNotEmpty()
                .allSatisfy(sentence -> assertThat(sentence).isNotBlank().doesNotStartWith("#"));
        assertThat(sentences.getFirst()).isEqualTo("Çivîk hêlînên xwe li ser daran ava dikin.");
    }

    @Test
    void aLanguageWithoutSentencesOrAnOddCodeHasNone() {
        assertThat(listening.sentences("fr")).isEmpty();
        assertThat(listening.sentences("../application")).isEmpty();
        assertThat(listening.sentences("KMR")).isEmpty();
    }

    @Test
    void theSentencesAreReadOnceAndKept() {
        assertThat(listening.sentences("kmr")).isSameAs(listening.sentences("kmr"));
    }

    @Test
    void aSentenceIsSpokenOnceAndThenServedFromMemory() {
        Optional<Result<SynthesisError, Speech>> first = listening.sentenceAudio("kmr", 0);
        Optional<Result<SynthesisError, Speech>> second = listening.sentenceAudio("kmr", 0);

        assertThat(first).contains(Result.ok(SPEECH));
        assertThat(second).contains(Result.ok(SPEECH));
        assertThat(spoken).containsExactly("Çivîk hêlînên xwe li ser daran ava dikin.");
    }

    @Test
    void aFailedSentenceIsTriedAgainNextTime() {
        answer = Result.err(new SynthesisError.Unavailable("down"));
        listening.sentenceAudio("kmr", 1);
        answer = Result.ok(SPEECH);

        assertThat(listening.sentenceAudio("kmr", 1)).contains(Result.ok(SPEECH));
        assertThat(spoken).hasSize(2);
    }

    @Test
    void thereIsNoSentenceOutsideTheList() {
        assertThat(listening.sentenceAudio("kmr", -1)).isEmpty();
        assertThat(listening.sentenceAudio("kmr", listening.sentences("kmr").size()))
                .isEmpty();
        assertThat(spoken).isEmpty();
    }

    @Test
    void aPreviewSpeaksTheTrimmedText() {
        assertThat(listening.preview("  Yekîtiya Ewropayê ")).isEqualTo(Result.ok(SPEECH));
        assertThat(spoken).containsExactly("Yekîtiya Ewropayê");
    }

    @Test
    void anEmptyOrLongPreviewIsRejectedWithoutReachingTheEngine() {
        assertThat(listening.preview(" ")).isInstanceOf(Result.Err.class);
        assertThat(listening.preview(null)).isInstanceOf(Result.Err.class);
        assertThat(listening.preview("a".repeat(ListeningService.MAX_PREVIEW_CHARS + 1)))
                .isInstanceOf(Result.Err.class);
        assertThat(spoken).isEmpty();
    }
}

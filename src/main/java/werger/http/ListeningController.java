package werger.http;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import werger.domain.Speech;
import werger.domain.User;
import werger.http.PronunciationFixController.Problem;
import werger.ports.SynthesisError;
import werger.service.ListeningService;

/** The listening page's sentences and audio. Only signed-in volunteers reach the engine. */
@RestController
public class ListeningController {
    private static final MediaType WAV = MediaType.parseMediaType("audio/wav");

    private final ListeningService listening;

    /** A test sentence and its position, which names its audio. */
    public record Sentence(int index, String text) {}

    /** Text to hear before sending or approving a suggestion. */
    public record Preview(String text) {}

    /**
     * Serves sentences and audio from {@code listening}.
     *
     * @param listening the sentences and the engine
     */
    public ListeningController(ListeningService listening) {
        this.listening = listening;
    }

    /**
     * The test sentences for a language.
     *
     * @param user the signed-in volunteer
     * @param language a language code; Kurmanji by default
     * @return the sentences in order
     */
    @GetMapping("/listening/sentences")
    public List<Sentence> sentences(User user, @RequestParam(defaultValue = "kmr") String language) {
        List<String> all = listening.sentences(language);
        return IntStream.range(0, all.size())
                .mapToObj(index -> new Sentence(index, all.get(index)))
                .toList();
    }

    /**
     * One test sentence, spoken.
     *
     * @param user the signed-in volunteer
     * @param index the sentence's position
     * @param language a language code; Kurmanji by default
     * @return the WAV audio, 404 for no such sentence, or 503 while the engine is unavailable
     */
    @GetMapping("/listening/sentences/{index}/audio")
    public ResponseEntity<Object> sentenceAudio(
            User user, @PathVariable int index, @RequestParam(defaultValue = "kmr") String language) {
        return listening
                .sentenceAudio(language, index)
                .map(result -> result.fold(ListeningController::failed, ListeningController::audio))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A respelling, spoken, so it can be heard before it is sent or approved.
     *
     * @param user the signed-in volunteer
     * @param preview the text to speak
     * @return the WAV audio, 422 for text that cannot be spoken, or 503 while the engine is unavailable
     */
    @PostMapping("/listening/preview")
    public ResponseEntity<Object> preview(User user, @RequestBody Preview preview) {
        return listening.preview(preview.text()).fold(ListeningController::failed, ListeningController::audio);
    }

    /**
     * The audio as a WAV response.
     *
     * @param speech the audio
     * @return 200 with the WAV file
     */
    private static ResponseEntity<Object> audio(Speech speech) {
        return ResponseEntity.ok().contentType(WAV).body(speech.wav());
    }

    /**
     * Why there is no audio.
     *
     * @param error the engine's refusal
     * @return 503 when trying later may work, 422 when it will not
     */
    static ResponseEntity<Object> failed(SynthesisError error) {
        return switch (error) {
            case SynthesisError.Unavailable _ ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new Problem("speech-unavailable", "the voice is not available right now"));
            case SynthesisError.Rejected(String detail) ->
                ResponseEntity.unprocessableContent().body(new Problem("speech-rejected", detail));
        };
    }
}

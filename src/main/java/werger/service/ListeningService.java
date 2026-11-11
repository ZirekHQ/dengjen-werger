package werger.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import werger.domain.Speech;
import werger.ports.Result;
import werger.ports.SpeechSynthesizer;
import werger.ports.SynthesisError;

/**
 * The test sentences volunteers listen to, and their audio. A sentence's audio is kept once made: the voice only
 * changes when Werger is redeployed with a new engine, which starts with an empty cache.
 */
public class ListeningService {
    /** Longer text is not a word or phrase to preview, and would tie up the engine. */
    public static final int MAX_PREVIEW_CHARS = 300;

    private final SpeechSynthesizer synthesizer;
    private final Map<String, List<String>> sentences = new ConcurrentHashMap<>();
    private final Map<String, Speech> spoken = new ConcurrentHashMap<>();

    /**
     * Speaks through {@code synthesizer}.
     *
     * @param synthesizer the engine
     */
    public ListeningService(SpeechSynthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    /**
     * The test sentences for a language, in order.
     *
     * @param language a language code, such as {@code kmr}
     * @return the sentences, empty for a language without any
     */
    public List<String> sentences(String language) {
        return sentences.computeIfAbsent(language, ListeningService::load);
    }

    /**
     * The audio of one test sentence.
     *
     * @param language a language code
     * @param index the sentence's position in {@link #sentences(String)}
     * @return the audio, empty when there is no such sentence, or why the engine made none
     */
    public Optional<Result<SynthesisError, Speech>> sentenceAudio(String language, int index) {
        List<String> all = sentences(language);
        if (index < 0 || index >= all.size()) {
            return Optional.empty();
        }
        String text = all.get(index);
        Speech cached = spoken.get(text);
        if (cached != null) {
            return Optional.of(Result.ok(cached));
        }
        Result<SynthesisError, Speech> result = synthesizer.speak(text);
        if (result instanceof Result.Ok<SynthesisError, Speech>(Speech speech)) {
            spoken.put(text, speech);
        }
        return Optional.of(result);
    }

    /**
     * Speaks a volunteer's respelling so they, or a reviewer, can hear it before deciding.
     *
     * @param text the respelling
     * @return the audio, or why there is none; text that is blank or too long is rejected
     */
    public Result<SynthesisError, Speech> preview(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_PREVIEW_CHARS) {
            return Result.err(
                    new SynthesisError.Rejected("type between 1 and " + MAX_PREVIEW_CHARS + " characters to hear"));
        }
        return synthesizer.speak(trimmed);
    }

    /**
     * Reads {@code listening/<language>.txt}, skipping blank lines and {@code #} comments. Only codes of letters and
     * hyphens are looked up, so a request cannot name another resource.
     *
     * @param language a language code
     * @return the sentences
     */
    private static List<String> load(String language) {
        if (!language.matches("[a-z]{2,3}(-[a-z0-9]{1,8})*")) {
            return List.of();
        }
        try (InputStream in = ListeningService.class.getResourceAsStream("/listening/" + language + ".txt")) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

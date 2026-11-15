package werger.ports;

import werger.domain.Speech;

/** Turns text into speech with the voice volunteers are correcting. */
public interface SpeechSynthesizer {
    /**
     * Speaks {@code text}.
     *
     * @param text the text to speak
     * @return the audio, or why there is none
     */
    Result<SynthesisError, Speech> speak(String text);
}

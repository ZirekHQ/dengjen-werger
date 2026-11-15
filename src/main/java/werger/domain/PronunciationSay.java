package werger.domain;

/** How a pronunciation fix says its word: the {@code say} column of dengjen's lexicon file. */
public sealed interface PronunciationSay {
    /** Kurmanji words the phonemizer reads in place of the written form, with its usual rules and stress. */
    record Respelling(String words) implements PronunciationSay {}

    /** IPA the phonemizer uses as is; written between slashes in the lexicon file. */
    record Phonemes(String ipa) implements PronunciationSay {}
}

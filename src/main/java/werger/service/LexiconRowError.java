package werger.service;

/** Why a volunteer's input can't become a lexicon row. Each case maps to a message the volunteer can act on. */
public sealed interface LexiconRowError {
    record EmptyWritten() implements LexiconRowError {}

    /** The written form holds something other than letters: a space, digit or punctuation. */
    record NotOneWord(String written) implements LexiconRowError {}

    record EmptySay() implements LexiconRowError {}

    /** Only one end of the phonemes has a slash. */
    record UnclosedPhonemes() implements LexiconRowError {}

    record EmptyPhonemes() implements LexiconRowError {}

    /** The characters a respelling or phonemes may not hold, each listed once in order of first use. */
    record ForbiddenCharacters(String characters) implements LexiconRowError {}
}

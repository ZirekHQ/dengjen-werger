package werger.service;

import java.text.Normalizer;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationSay;
import werger.ports.Result;

/**
 * Turns a volunteer's input into a row of dengjen's lexicon file ({@code crates/text/kurmanji-phonemizer/lexicon/kmr.tsv},
 * format in {@code docs/modules/ROOT/pages/pronunciation-lexicon.adoc}). The checks here mirror the parser there so a
 * volunteer hears about a problem in the form, not on a failed pull request. The phoneme symbol set is left to that
 * parser, which the lexicon pull request's CI runs: copying it here would drift whenever the voice's set changes.
 */
public final class LexiconRow {
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}\\p{Cc}]+");
    private static final String RESPELLING_LETTERS = "abcdefghijklmnopqrstuvwxyzîêûçşöü";

    private LexiconRow() {}

    /**
     * Checks the {@code written} column: one word of letters, trimmed and composed to NFC. The case is kept, because a
     * capitalised entry matches only that spelling.
     */
    public static Result<LexiconRowError, String> parseWritten(String raw) {
        String written = nfc(raw).strip();
        if (written.isEmpty()) {
            return Result.err(new LexiconRowError.EmptyWritten());
        }
        if (!written.codePoints().allMatch(LexiconRow::isWordChar)) {
            return Result.err(new LexiconRowError.NotOneWord(written));
        }
        return Result.ok(written);
    }

    /**
     * Checks the {@code say} column: phonemes between two slashes, or otherwise a respelling in Kurmanji letters,
     * spaces, hyphens and apostrophes. Runs of whitespace collapse to one space.
     */
    public static Result<LexiconRowError, PronunciationSay> parseSay(String raw) {
        String say = nfc(raw).strip();
        if (say.isEmpty()) {
            return Result.err(new LexiconRowError.EmptySay());
        }
        boolean opens = say.startsWith("/");
        boolean closes = say.length() > 1 && say.endsWith("/");
        if (opens && closes) {
            return parsePhonemes(say.substring(1, say.length() - 1).strip());
        }
        if (opens || say.endsWith("/")) {
            return Result.err(new LexiconRowError.UnclosedPhonemes());
        }
        return forbidden(say, LexiconRow::isRespellingChar)
                .<Result<LexiconRowError, PronunciationSay>>map(Result::err)
                .orElseGet(() -> Result.ok(new PronunciationSay.Respelling(collapseSpaces(say))));
    }

    /** The fix as one line of the lexicon file, without the line break. */
    public static String render(PronunciationFix fix) {
        String say = switch (fix.say()) {
            case PronunciationSay.Respelling(String words) -> words;
            case PronunciationSay.Phonemes(String ipa) -> "/" + ipa + "/";
        };
        String note = fix.note().map(LexiconRow::collapseSpaces).orElse("");
        return note.isEmpty() ? fix.written() + "\t" + say : fix.written() + "\t" + say + "\t" + note;
    }

    private static Result<LexiconRowError, PronunciationSay> parsePhonemes(String ipa) {
        if (ipa.isEmpty()) {
            return Result.err(new LexiconRowError.EmptyPhonemes());
        }
        return forbidden(ipa, c -> c == ' ' || !(Character.isWhitespace(c) || Character.isISOControl(c) || c == '/'))
                .<Result<LexiconRowError, PronunciationSay>>map(Result::err)
                .orElseGet(() -> Result.ok(new PronunciationSay.Phonemes(collapseSpaces(ipa))));
    }

    private static Optional<LexiconRowError> forbidden(String text, IntPredicate allowed) {
        String bad = text.codePoints()
                .filter(allowed.negate())
                .distinct()
                .mapToObj(Character::toString)
                .collect(Collectors.joining());
        return bad.isEmpty() ? Optional.empty() : Optional.of(new LexiconRowError.ForbiddenCharacters(bad));
    }

    // Matches the Rust parser's is_word_char: alphabetic, or a combining mark left over after normalisation.
    private static boolean isWordChar(int c) {
        int type = Character.getType(c);
        return Character.isAlphabetic(c)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isRespellingChar(int c) {
        return c == ' ' || c == '-' || c == '\'' || RESPELLING_LETTERS.indexOf(Character.toLowerCase(c)) >= 0;
    }

    // Includes Unicode separators and controls (U+0085, U+2028, U+2029), so no line break of any kind reaches the file.
    private static String collapseSpaces(String text) {
        return SPACES.matcher(text.strip()).replaceAll(" ");
    }

    private static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }
}

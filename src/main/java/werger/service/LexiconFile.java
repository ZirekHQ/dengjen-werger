package werger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import werger.domain.PronunciationFix;

/** Reads and rewrites dengjen's lexicon file, whose first line is a header and whose rows are keyed by written form. */
public final class LexiconFile {
    static final String HEADER = "written\tsay\tnote";

    private LexiconFile() {}

    /** True when the file already has exactly the row the fix renders to. */
    public static boolean contains(String content, PronunciationFix fix) {
        return content.lines().skip(1).anyMatch(LexiconRow.render(fix)::equals);
    }

    /**
     * Writes each fix's row over the row for the same written form, or after the last row when there is none. Every
     * other line is kept as it is, so the pull request shows only the rows that change.
     */
    public static String apply(String content, List<PronunciationFix> fixes) {
        List<String> lines = new ArrayList<>(content.lines().toList());
        if (lines.isEmpty()) {
            lines.add(HEADER);
        }
        for (PronunciationFix fix : fixes) {
            String row = LexiconRow.render(fix);
            IntStream.range(1, lines.size())
                    .filter(i -> writtenOf(lines.get(i)).equals(fix.written()))
                    .findFirst()
                    .ifPresentOrElse(i -> lines.set(i, row), () -> lines.add(row));
        }
        return String.join("\n", lines) + "\n";
    }

    private static String writtenOf(String line) {
        int tab = line.indexOf('\t');
        return tab < 0 ? line : line.substring(0, tab);
    }
}

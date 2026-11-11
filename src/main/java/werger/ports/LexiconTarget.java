package werger.ports;

import java.net.URI;

/** The repository that holds a language's pronunciation lexicon, and takes changes to it as a pull request. */
public interface LexiconTarget {
    /** The lexicon file as it was at {@code revision} of the default branch. */
    record Snapshot(String content, String revision) {}

    /** The lexicon file as it is now on the default branch. */
    Result<SourceError, Snapshot> current(String language);

    /**
     * Proposes {@code content} as the whole new lexicon file, as a change on top of {@code base}. A change that reached
     * the default branch after {@code base} was read stays in place when the proposal merges. One pull request stays
     * open per language: a later call replaces what the open one proposes rather than opening another.
     */
    Result<SourceError, URI> propose(String language, Snapshot base, String content, String description);
}

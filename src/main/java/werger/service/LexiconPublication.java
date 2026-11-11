package werger.service;

import java.net.URI;
import java.util.List;
import java.util.UUID;

public sealed interface LexiconPublication {
    /** Every approved fix is already in the lexicon, or there was none. */
    record UpToDate() implements LexiconPublication {}

    record Proposed(URI pullRequest, List<UUID> fixes) implements LexiconPublication {}
}

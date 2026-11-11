package werger.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A native speaker's suggestion for a word the voice says wrongly. Once a second speaker approves it, it becomes one
 * row of the lexicon file in dengjen.
 */
public record PronunciationFix(
        UUID id, String written, PronunciationSay say, Optional<String> note, UUID submitter, Instant submittedAt) {}

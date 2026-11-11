package werger.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** A second speaker's verdict on a pronunciation fix. */
public record PronunciationFixReview(
        UUID fix, UUID reviewer, ReviewVerdict verdict, Optional<String> comment, Instant reviewedAt) {}

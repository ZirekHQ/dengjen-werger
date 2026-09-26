package werger.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Review(
        UUID submission, UUID reviewer, ReviewVerdict verdict, Optional<String> comment, Instant reviewedAt) {}

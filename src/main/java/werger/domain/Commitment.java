package werger.domain;

import java.time.Instant;

public record Commitment(CommitmentTier tier, Instant startedAt) {}

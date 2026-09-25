package werger.domain;

import java.time.Instant;
import java.util.UUID;

public record PointsLedgerEntry(UUID user, UUID submission, PointsReason reason, int amount, Instant at) {}

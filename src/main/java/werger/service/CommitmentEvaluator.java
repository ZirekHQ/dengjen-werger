package werger.service;

import java.time.Duration;
import java.time.Instant;
import werger.domain.CommitmentTier;

/** Compares a contributor's progress against their commitment tier's quota, so callers don't encode tier numbers. */
public final class CommitmentEvaluator {
    private CommitmentEvaluator() {}

    public static int actionsRequired(CommitmentTier tier) {
        return switch (tier) {
            case LIGHT -> 1;
            case MEDIUM -> 5;
            case HEAVY -> 8;
        };
    }

    /** True when {@code actionsThisPeriod} meets the quota pro-rated to the time elapsed, rounded up. */
    public static boolean isOnTrack(CommitmentTier tier, int actionsThisPeriod, Instant now, Instant periodStart) {
        return actionsThisPeriod >= expectedActions(tier, Duration.between(periodStart, now));
    }

    // Integer ceiling division keeps boundary cases exact, which a double fraction would blur.
    private static long expectedActions(CommitmentTier tier, Duration elapsed) {
        long periodMillis = periodLength(tier).toMillis();
        long elapsedMillis = Math.clamp(elapsed.toMillis(), 0L, periodMillis);
        return Math.ceilDiv(actionsRequired(tier) * elapsedMillis, periodMillis);
    }

    private static Duration periodLength(CommitmentTier tier) {
        return switch (tier) {
            case LIGHT -> Duration.ofDays(1);
            case MEDIUM -> Duration.ofDays(7);
            case HEAVY -> Duration.ofDays(30);
        };
    }
}

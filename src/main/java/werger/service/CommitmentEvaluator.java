package werger.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import werger.domain.CommitmentTier;

/** Compares a contributor's progress against their commitment tier's quota, so callers don't encode tier numbers. */
public final class CommitmentEvaluator {
    private static final int REVIEWS_PER_SUBMISSION = 2;

    private CommitmentEvaluator() {}

    /** The calendar period, in the user's zone, that actions are counted over. {@code end} is exclusive. */
    public record PeriodWindow(Instant start, Instant end) {}

    /** The calendar day, week (Monday start) or month of the tier that contains {@code now} in {@code zone}. */
    public static PeriodWindow periodAt(CommitmentTier tier, Instant now, ZoneId zone) {
        LocalDate first = tier.periodFirstDay(now.atZone(zone).toLocalDate());
        return new PeriodWindow(
                first.atStartOfDay(zone).toInstant(),
                first.plus(tier.length()).atStartOfDay(zone).toInstant());
    }

    /**
     * True when the submissions and reviews counted in the current period meet the quota pro-rated to the time
     * elapsed, rounded up. A review counts as half a submission.
     */
    public static boolean isOnTrack(CommitmentTier tier, int submissions, int reviews, Instant now, ZoneId zone) {
        long done = (long) submissions * REVIEWS_PER_SUBMISSION + reviews;
        return done >= expectedUnits(tier, periodAt(tier, now, zone), now);
    }

    // Integer ceiling division keeps boundary cases exact, which a double fraction would blur.
    private static long expectedUnits(CommitmentTier tier, PeriodWindow window, Instant now) {
        long periodMillis = Duration.between(window.start(), window.end()).toMillis();
        long elapsedMillis = Duration.between(window.start(), now).toMillis();
        return Math.ceilDiv((long) tier.submissionQuota() * REVIEWS_PER_SUBMISSION * elapsedMillis, periodMillis);
    }
}

package werger.service;

import java.util.List;
import werger.domain.ReviewVerdict;
import werger.domain.Role;

/**
 * Decides whether a user's approvals may be pushed upstream without a countersign.
 *
 * <p>Maintainers and Reviewers (seeded at launch) are trusted unconditionally, which solves the day-one bootstrap
 * problem. A Contributor earns trust from their record: the lower bound of the Wilson score interval on their approval
 * rate, over their most recent decided submissions. The lower bound is a pessimistic estimate, so it needs both a high
 * approval rate and enough evidence. There is no fixed count: a short perfect record is not enough, each rejection
 * costs a few extra approvals to recover from, and a mostly-rejected record never qualifies. Only the latest
 * {@link #WINDOW} outcomes count, so old rejections age out and old approvals cannot shield recent bad submissions.
 */
public final class TrustLevel {
    static final int WINDOW = 20;
    static final double TRUSTED_SCORE = 0.6;
    private static final double Z = 1.96; // 95% confidence

    private TrustLevel() {}

    /** {@code outcomes} lists the verdicts on the user's decided submissions, oldest first. */
    public static boolean isTrusted(Role role, List<ReviewVerdict> outcomes) {
        return switch (role) {
            case MAINTAINER, REVIEWER -> true;
            case CONTRIBUTOR -> trustScore(outcomes) >= TRUSTED_SCORE;
        };
    }

    /** A conservative approval rate in [0, 1): 0 with no history, approaching 1 only with a long clean record. */
    public static double trustScore(List<ReviewVerdict> outcomes) {
        List<ReviewVerdict> recent = lastDecided(outcomes);
        return recent.isEmpty() ? 0.0 : wilsonLowerBound(approvedIn(recent), recent.size());
    }

    private static List<ReviewVerdict> lastDecided(List<ReviewVerdict> outcomes) {
        return outcomes.subList(Math.max(0, outcomes.size() - WINDOW), outcomes.size());
    }

    private static long approvedIn(List<ReviewVerdict> outcomes) {
        return outcomes.stream().filter(ReviewVerdict.APPROVED::equals).count();
    }

    private static double wilsonLowerBound(long approved, int total) {
        double rate = (double) approved / total;
        double z2 = Z * Z;
        double centre = rate + z2 / (2 * total);
        double margin = Z * Math.sqrt(rate * (1 - rate) / total + z2 / (4.0 * total * total));
        return (centre - margin) / (1 + z2 / total);
    }
}

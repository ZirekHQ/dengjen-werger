package werger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static werger.domain.ReviewVerdict.APPROVED;
import static werger.domain.ReviewVerdict.REJECTED;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import werger.domain.Review;
import werger.domain.ReviewVerdict;
import werger.domain.Role;

class TrustLevelTest {
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    private static Review review(ReviewVerdict verdict, int dayOffset) {
        return new Review(
                UUID.randomUUID(), UUID.randomUUID(), verdict, Optional.empty(), BASE.plus(dayOffset, ChronoUnit.DAYS));
    }

    private static List<Review> run(int count, ReviewVerdict verdict) {
        return IntStream.range(0, count).mapToObj(day -> review(verdict, day)).toList();
    }

    /** Places {@code newer} after {@code older} in time, regardless of list order. */
    private static List<Review> then(List<Review> older, List<Review> newer) {
        List<Review> shifted = newer.stream()
                .map(r -> review(r.verdict(), 1_000 + newer.indexOf(r)))
                .toList();
        return Stream.concat(older.stream(), shifted.stream()).toList();
    }

    @Test
    void maintainerIsAlwaysTrustedEvenWithNoHistory() {
        assertThat(TrustLevel.isTrusted(Role.MAINTAINER, List.of())).isTrue();
    }

    @Test
    void reviewerSeededAtLaunchIsAlwaysTrustedEvenIfRejected() {
        assertThat(TrustLevel.isTrusted(Role.REVIEWER, run(10, REJECTED))).isTrue();
    }

    @Test
    void contributorWithNoHistoryIsNotTrusted() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, List.of())).isFalse();
        assertThat(TrustLevel.trustScore(List.of())).isZero();
    }

    @Test
    void trustComesFromTheSampleSizeNotAFixedCount() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, run(8, APPROVED))).isFalse();
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, run(9, APPROVED))).isTrue();
    }

    @Test
    void oneRejectionDelaysTrustInsteadOfBlockingIt() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(13, APPROVED), run(1, REJECTED))))
                .isFalse();
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(14, APPROVED), run(1, REJECTED))))
                .isTrue();
    }

    @Test
    void aContributorWhoIsMostlyRejectedIsNotTrusted() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(3, APPROVED), run(7, REJECTED))))
                .isFalse();
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, run(10, REJECTED))).isFalse();
    }

    @Test
    void oldRejectionsAgeOutOfTheWindow() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(6, REJECTED), run(20, APPROVED))))
                .isTrue();
    }

    @Test
    void oldApprovalsDoNotShieldRecentBadBehaviour() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(20, APPROVED), run(10, REJECTED))))
                .isFalse();
    }

    @Test
    void trustScoreRisesWithEvidenceButNeverReachesOne() {
        double small = TrustLevel.trustScore(run(6, APPROVED));
        double large = TrustLevel.trustScore(run(20, APPROVED));

        assertThat(large).isGreaterThan(small).isLessThan(1.0);
    }

    @Test
    void inputOrderDoesNotChangeWhichOutcomesAreTheLatest() {
        List<Review> recentFirst = then(run(20, APPROVED), run(10, REJECTED)).reversed();

        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, recentFirst)).isFalse();
    }
}

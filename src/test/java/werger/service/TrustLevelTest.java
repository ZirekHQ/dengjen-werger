package werger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static werger.domain.ReviewVerdict.APPROVED;
import static werger.domain.ReviewVerdict.REJECTED;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import werger.domain.ReviewVerdict;
import werger.domain.Role;

class TrustLevelTest {
    private static List<ReviewVerdict> run(int count, ReviewVerdict verdict) {
        return Collections.nCopies(count, verdict);
    }

    /** Oldest first, matching how a repo would return a contributor's decided submissions. */
    private static List<ReviewVerdict> then(List<ReviewVerdict> older, List<ReviewVerdict> newer) {
        return Stream.concat(older.stream(), newer.stream()).toList();
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
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, run(5, APPROVED))).isFalse();
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, run(6, APPROVED))).isTrue();
    }

    @Test
    void oneRejectionDelaysTrustInsteadOfBlockingIt() {
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(9, APPROVED), run(1, REJECTED))))
                .isFalse();
        assertThat(TrustLevel.isTrusted(Role.CONTRIBUTOR, then(run(10, APPROVED), run(1, REJECTED))))
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
}

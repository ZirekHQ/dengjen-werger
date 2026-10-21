package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import werger.domain.CommitmentTier;

class CommitmentEvaluatorTest {
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void actionsRequiredIsTheQuotaPerTier() {
        assertThat(CommitmentEvaluator.actionsRequired(CommitmentTier.LIGHT)).isEqualTo(1);
        assertThat(CommitmentEvaluator.actionsRequired(CommitmentTier.MEDIUM)).isEqualTo(5);
        assertThat(CommitmentEvaluator.actionsRequired(CommitmentTier.HEAVY)).isEqualTo(8);
    }

    @Test
    void onTrackWhenActionsMeetTheProRatedTarget() {
        Instant noon = Instant.parse("2026-09-01T12:00:00Z");

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 1, noon, START))
                .isTrue();
    }

    @Test
    void behindWhenNoActionsPastTheHalfwayPoint() {
        Instant noon = Instant.parse("2026-09-01T12:00:00Z");

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 0, noon, START))
                .isFalse();
    }

    @Test
    void onTrackAtTheExactStartOfThePeriodWithNoActions() {
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.HEAVY, 0, START, START))
                .isTrue();
    }

    @Test
    void onTrackBeforeThePeriodStartsWithNoActions() {
        Instant before = START.minusSeconds(3_600);

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.MEDIUM, 0, before, START))
                .isTrue();
    }

    @Test
    void expectedCountRoundsUpSoAnyElapsedTimeDemandsTheNextAction() {
        Instant oneSecondIn = START.plusSeconds(1);

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 0, oneSecondIn, START))
                .isFalse();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 1, oneSecondIn, START))
                .isTrue();
    }

    @Test
    void mediumTierProRatesAcrossAWeek() {
        Instant midweek = START.plusSeconds(3L * 24 * 3_600 + 12 * 3_600); // 3.5 of 7 days → ceil(2.5) = 3

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.MEDIUM, 3, midweek, START))
                .isTrue();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.MEDIUM, 2, midweek, START))
                .isFalse();
    }

    @Test
    void heavyTierProRatesAcrossThirtyDays() {
        Instant day15 = START.plusSeconds(15L * 24 * 3_600); // half of 30 days → 4 of 8

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.HEAVY, 4, day15, START))
                .isTrue();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.HEAVY, 3, day15, START))
                .isFalse();
    }

    @Test
    void expectedCountIsCappedAtTheFullQuotaOncethePeriodHasEnded() {
        Instant longAfter = START.plusSeconds(365L * 24 * 3_600);

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.MEDIUM, 5, longAfter, START))
                .isTrue();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.MEDIUM, 4, longAfter, START))
                .isFalse();
    }
}

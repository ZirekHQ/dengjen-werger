package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import werger.domain.CommitmentTier;
import werger.service.CommitmentEvaluator.PeriodWindow;

class CommitmentEvaluatorTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private static boolean onTrack(CommitmentTier tier, int submissions, int reviews, Instant now) {
        return CommitmentEvaluator.isOnTrack(tier, submissions, reviews, now, UTC);
    }

    @Test
    void lightPeriodIsTheCalendarDayInTheUsersZone() {
        Instant now = Instant.parse("2026-09-01T02:00:00Z");

        PeriodWindow window = CommitmentEvaluator.periodAt(CommitmentTier.LIGHT, now, ZoneId.of("America/Los_Angeles"));

        assertThat(window.start()).isEqualTo(Instant.parse("2026-08-31T07:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-01T07:00:00Z"));
    }

    @Test
    void lightPeriodIsTwentyThreeHoursOnTheSpringForwardDay() {
        Instant now = Instant.parse("2026-03-08T12:00:00Z");

        PeriodWindow window = CommitmentEvaluator.periodAt(CommitmentTier.LIGHT, now, ZoneId.of("America/New_York"));

        assertThat(window.start()).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
    }

    @Test
    void proRatingUsesTheRealLengthOfTheSpringForwardDay() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant midpoint = Instant.parse("2026-03-08T16:30:00Z"); // start 05:00Z + 11.5h, half of the 23h day

        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 0, 0, midpoint.minusSeconds(60), newYork))
                .isFalse();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 0, 1, midpoint, newYork))
                .isTrue();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 0, 1, midpoint.plusSeconds(60), newYork))
                .isFalse();
        assertThat(CommitmentEvaluator.isOnTrack(CommitmentTier.LIGHT, 0, 2, midpoint.plusSeconds(60), newYork))
                .isTrue();
    }

    @Test
    void mediumPeriodIsTheMondayStartedCalendarWeek() {
        PeriodWindow window = CommitmentEvaluator.periodAt(CommitmentTier.MEDIUM, START, UTC);

        assertThat(window.start()).isEqualTo(Instant.parse("2026-08-31T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
    }

    @Test
    void heavyPeriodIsTheCalendarMonth() {
        Instant midMonth = Instant.parse("2026-09-15T12:00:00Z");

        PeriodWindow window = CommitmentEvaluator.periodAt(CommitmentTier.HEAVY, midMonth, UTC);

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void onTrackWhenActionsMeetTheProRatedTarget() {
        Instant noon = Instant.parse("2026-09-01T12:00:00Z");

        assertThat(onTrack(CommitmentTier.LIGHT, 1, 0, noon)).isTrue();
    }

    @Test
    void behindWhenNoActionsPastTheHalfwayPoint() {
        Instant noon = Instant.parse("2026-09-01T12:00:00Z");

        assertThat(onTrack(CommitmentTier.LIGHT, 0, 0, noon)).isFalse();
    }

    @Test
    void onTrackAtTheExactStartOfThePeriodWithNoActions() {
        assertThat(onTrack(CommitmentTier.HEAVY, 0, 0, START)).isTrue();
    }

    @Test
    void expectedCountRoundsUpSoAnyElapsedTimeDemandsTheNextAction() {
        Instant oneSecondIn = START.plusSeconds(1);

        assertThat(onTrack(CommitmentTier.LIGHT, 0, 0, oneSecondIn)).isFalse();
        assertThat(onTrack(CommitmentTier.LIGHT, 0, 1, oneSecondIn)).isTrue();
    }

    @Test
    void twoReviewsCountAsOneSubmissionTowardTheLightQuota() {
        Instant endOfDay = START.plusSeconds(24 * 3_600 - 1);

        assertThat(onTrack(CommitmentTier.LIGHT, 0, 2, endOfDay)).isTrue();
        assertThat(onTrack(CommitmentTier.LIGHT, 0, 1, endOfDay)).isFalse();
    }

    @Test
    void mediumTierProRatesAcrossTheCalendarWeek() {
        Instant midweek = Instant.parse("2026-09-03T12:00:00Z"); // 3.5 of 7 days → 5 of 10 review units

        assertThat(onTrack(CommitmentTier.MEDIUM, 2, 1, midweek)).isTrue();
        assertThat(onTrack(CommitmentTier.MEDIUM, 2, 0, midweek)).isFalse();
    }

    @Test
    void heavyTierProRatesAcrossTheCalendarMonth() {
        Instant halfway = Instant.parse("2026-09-16T00:00:00Z"); // 15 of 30 days → 8 of 16 review units

        assertThat(onTrack(CommitmentTier.HEAVY, 4, 0, halfway)).isTrue();
        assertThat(onTrack(CommitmentTier.HEAVY, 3, 1, halfway)).isFalse();
    }

    @Test
    void expectedCountReachesTheFullQuotaAtTheEndOfThePeriod() {
        Instant lastSecond = Instant.parse("2026-09-06T23:59:59Z");

        assertThat(onTrack(CommitmentTier.MEDIUM, 5, 0, lastSecond)).isTrue();
        assertThat(onTrack(CommitmentTier.MEDIUM, 4, 1, lastSecond)).isFalse();
    }

    @Test
    void quotaResetsWhenTheNextPeriodStarts() {
        Instant nextWeek = Instant.parse("2026-09-07T00:00:00Z");

        assertThat(onTrack(CommitmentTier.MEDIUM, 0, 0, nextWeek)).isTrue();
    }
}

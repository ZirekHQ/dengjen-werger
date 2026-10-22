package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import werger.domain.StreakState;

class StreakCalculatorTest {
    private static final UUID USER = UUID.randomUUID();

    private static StreakState state(int current, int longest, String lastMet) {
        return new StreakState(USER, current, longest, Optional.of(LocalDate.parse(lastMet)));
    }

    private static StreakState met(StreakState state, String period, String previousPeriod) {
        return StreakCalculator.onPeriodMet(state, LocalDate.parse(period), LocalDate.parse(previousPeriod));
    }

    @Test
    void consecutivePeriodExtendsTheStreak() {
        var updated = met(state(3, 3, "2026-09-10"), "2026-09-11", "2026-09-10");

        assertThat(updated.current()).isEqualTo(4);
        assertThat(updated.longest()).isEqualTo(4);
        assertThat(updated.lastMetPeriod()).contains(LocalDate.parse("2026-09-11"));
    }

    @Test
    void gapResetsCurrentButKeepsLongest() {
        var updated = met(state(5, 5, "2026-09-05"), "2026-09-11", "2026-09-10");

        assertThat(updated.current()).isEqualTo(1);
        assertThat(updated.longest()).isEqualTo(5);
    }

    @Test
    void secondCallForAnAlreadyMetPeriodIsANoOp() {
        var before = state(4, 4, "2026-09-11");

        assertThat(met(before, "2026-09-11", "2026-09-10")).isEqualTo(before);
    }

    @Test
    void firstEverPeriodStartsAStreakOfOne() {
        var fresh = new StreakState(USER, 0, 0, Optional.empty());

        var updated = met(fresh, "2026-09-11", "2026-09-10");

        assertThat(updated.current()).isEqualTo(1);
        assertThat(updated.longest()).isEqualTo(1);
    }

    @Test
    void extendingBelowTheLongestLeavesLongestUntouched() {
        var updated = met(state(2, 9, "2026-09-10"), "2026-09-11", "2026-09-10");

        assertThat(updated.current()).isEqualTo(3);
        assertThat(updated.longest()).isEqualTo(9);
    }

    @Test
    void aPeriodOlderThanTheLastMetOneIsIgnored() {
        var before = state(4, 4, "2026-09-11");

        assertThat(met(before, "2026-09-08", "2026-09-10")).isEqualTo(before);
    }

    @Test
    void weeklyStreakExtendsWhenTheLastMetPeriodIsThePreviousWeek() {
        var updated = met(state(2, 2, "2026-08-31"), "2026-09-07", "2026-08-31");

        assertThat(updated.current()).isEqualTo(3);
    }

    @Test
    void monthlyStreakResetsWhenAMonthWasSkipped() {
        var updated = met(state(4, 4, "2026-07-01"), "2026-09-01", "2026-08-01");

        assertThat(updated.current()).isEqualTo(1);
        assertThat(updated.longest()).isEqualTo(4);
    }
}

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

    @Test
    void consecutivePeriodExtendsTheStreak() {
        var updated = StreakCalculator.onPeriodMet(state(3, 3, "2026-09-10"), LocalDate.parse("2026-09-11"));

        assertThat(updated.current()).isEqualTo(4);
        assertThat(updated.longest()).isEqualTo(4);
        assertThat(updated.lastMetPeriod()).contains(LocalDate.parse("2026-09-11"));
    }

    @Test
    void gapResetsCurrentButKeepsLongest() {
        var updated = StreakCalculator.onPeriodMet(state(5, 5, "2026-09-05"), LocalDate.parse("2026-09-11"));

        assertThat(updated.current()).isEqualTo(1);
        assertThat(updated.longest()).isEqualTo(5);
    }

    @Test
    void secondCallForAnAlreadyMetPeriodIsANoOp() {
        var before = state(4, 4, "2026-09-11");

        assertThat(StreakCalculator.onPeriodMet(before, LocalDate.parse("2026-09-11")))
                .isEqualTo(before);
    }

    @Test
    void firstEverPeriodStartsAStreakOfOne() {
        var fresh = new StreakState(USER, 0, 0, Optional.empty());

        var updated = StreakCalculator.onPeriodMet(fresh, LocalDate.parse("2026-09-11"));

        assertThat(updated.current()).isEqualTo(1);
        assertThat(updated.longest()).isEqualTo(1);
    }

    @Test
    void extendingBelowTheLongestLeavesLongestUntouched() {
        var updated = StreakCalculator.onPeriodMet(state(2, 9, "2026-09-10"), LocalDate.parse("2026-09-11"));

        assertThat(updated.current()).isEqualTo(3);
        assertThat(updated.longest()).isEqualTo(9);
    }

    @Test
    void aPeriodOlderThanTheLastMetOneIsIgnored() {
        var before = state(4, 4, "2026-09-11");

        assertThat(StreakCalculator.onPeriodMet(before, LocalDate.parse("2026-09-08")))
                .isEqualTo(before);
    }
}

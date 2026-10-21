package werger.service;

import java.time.LocalDate;
import java.util.Optional;
import werger.domain.StreakState;

/** Advances a contributor's streak when a commitment period is met. */
public final class StreakCalculator {
    private StreakCalculator() {}

    /** Idempotent per period; a period at or before the last one met leaves the state unchanged. */
    public static StreakState onPeriodMet(StreakState state, LocalDate period) {
        return state.lastMetPeriod().filter(last -> !period.isAfter(last)).isPresent() ? state : advance(state, period);
    }

    private static StreakState advance(StreakState state, LocalDate period) {
        int current = isConsecutive(state.lastMetPeriod(), period) ? state.current() + 1 : 1;
        return new StreakState(state.user(), current, Math.max(state.longest(), current), Optional.of(period));
    }

    private static boolean isConsecutive(Optional<LocalDate> lastMet, LocalDate period) {
        return lastMet.filter(last -> last.plusDays(1).isEqual(period)).isPresent();
    }
}

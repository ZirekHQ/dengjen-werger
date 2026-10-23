package werger.service;

import java.time.LocalDate;
import java.util.Optional;
import werger.domain.StreakState;

/** Advances a contributor's streak when a commitment period is met. */
public final class StreakCalculator {
    private StreakCalculator() {}

    /**
     * Idempotent per period; a period at or before the last one met leaves the state unchanged. {@code period} and
     * {@code previousPeriod} are the first days of the met period and of the one immediately before it in the user's
     * tier, so the streak extends only when the last met period is exactly {@code previousPeriod}.
     */
    public static StreakState onPeriodMet(StreakState state, LocalDate period, LocalDate previousPeriod) {
        return state.lastMetPeriod().filter(last -> !period.isAfter(last)).isPresent()
                ? state
                : advance(state, period, previousPeriod);
    }

    private static StreakState advance(StreakState state, LocalDate period, LocalDate previousPeriod) {
        int current = state.lastMetPeriod().filter(previousPeriod::isEqual).isPresent() ? state.current() + 1 : 1;
        return new StreakState(state.user(), current, Math.max(state.longest(), current), Optional.of(period));
    }
}

package werger.domain;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

import java.time.LocalDate;
import java.time.Period;
import java.util.function.UnaryOperator;

/** Each tier's quota in submissions per calendar period, where two reviews count as one submission. */
public enum CommitmentTier {
    LIGHT(1, Period.ofDays(1), day -> day),
    MEDIUM(5, Period.ofWeeks(1), day -> day.with(previousOrSame(MONDAY))),
    HEAVY(8, Period.ofMonths(1), day -> day.withDayOfMonth(1));

    private final int submissionQuota;
    private final Period length;
    private final UnaryOperator<LocalDate> periodFloor;

    CommitmentTier(int submissionQuota, Period length, UnaryOperator<LocalDate> periodFloor) {
        this.submissionQuota = submissionQuota;
        this.length = length;
        this.periodFloor = periodFloor;
    }

    public int submissionQuota() {
        return submissionQuota;
    }

    public Period length() {
        return length;
    }

    /** First calendar day of the period containing {@code day}. */
    public LocalDate periodFirstDay(LocalDate day) {
        return periodFloor.apply(day);
    }
}

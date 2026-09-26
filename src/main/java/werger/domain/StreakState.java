package werger.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public record StreakState(UUID user, int current, int longest, Optional<LocalDate> lastMetPeriod) {}

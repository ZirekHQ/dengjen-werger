package werger.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import werger.adapters.db.WorkItemRepo;
import werger.domain.WorkItem;

/** Leases the first candidate the caller wins; the database, not this class, decides who wins a contested item. */
public class LeaseService {
    static final Duration LEASE_WINDOW = Duration.ofMinutes(30);

    private final WorkItemRepo repo;
    private final Clock clock;

    public LeaseService(WorkItemRepo repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * Takes an already-fetched candidate list so leasing for translation and dispatch for review can share one FIFO
     * selection query instead of two.
     */
    public Optional<WorkItem> leaseNext(List<WorkItem> candidates, UUID userId) {
        // Lazy stream: stops at the first win, so only the attempts up to it touch the database.
        return candidates.stream()
                .filter(candidate ->
                        repo.tryLease(candidate.id(), userId, clock.instant().plus(LEASE_WINDOW)))
                .findFirst();
    }
}

package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.WorkItemRepo;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;

class LeaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    /** Hand-rolled stub: the lease race itself is proven against real Postgres in WorkItemRepoIT. */
    private static final class StubRepo extends WorkItemRepo {
        final Set<UUID> winnable;
        final List<UUID> attempted = new ArrayList<>();
        Instant lastExpiry;

        StubRepo(Set<UUID> winnable) {
            super(new JdbcTemplate());
            this.winnable = winnable;
        }

        @Override
        public boolean tryLease(UUID id, UUID userId, Instant expiresAt) {
            attempted.add(id);
            lastExpiry = expiresAt;
            return winnable.contains(id);
        }
    }

    private static WorkItem item() {
        return new WorkItem(
                UUID.randomUUID(), "crowdin", "1", "kmr", "Hello", Optional.empty(), new WorkItemStatus.Available());
    }

    private static LeaseService service(StubRepo repo) {
        return new LeaseService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void leasesTheFirstCandidateWhenItIsFree() {
        WorkItem first = item();
        WorkItem second = item();
        StubRepo repo = new StubRepo(Set.of(first.id(), second.id()));

        assertThat(service(repo).leaseNext(List.of(first, second), USER)).contains(first);
        assertThat(repo.attempted).containsExactly(first.id());
    }

    @Test
    void skipsCandidatesAnotherUserWonTheRaceFor() {
        WorkItem taken = item();
        WorkItem free = item();
        StubRepo repo = new StubRepo(Set.of(free.id()));

        assertThat(service(repo).leaseNext(List.of(taken, free), USER)).contains(free);
        assertThat(repo.attempted).containsExactly(taken.id(), free.id());
    }

    @Test
    void returnsNothingWhenEveryCandidateWasTaken() {
        StubRepo repo = new StubRepo(Set.of());

        assertThat(service(repo).leaseNext(List.of(item(), item()), USER)).isEmpty();
    }

    @Test
    void returnsNothingWithoutCandidates() {
        StubRepo repo = new StubRepo(Set.of());

        assertThat(service(repo).leaseNext(List.of(), USER)).isEmpty();
        assertThat(repo.attempted).isEmpty();
    }

    @Test
    void leaseExpiresThirtyMinutesFromNow() {
        WorkItem only = item();
        StubRepo repo = new StubRepo(Set.of(only.id()));

        service(repo).leaseNext(List.of(only), USER);

        assertThat(repo.lastExpiry).isEqualTo(NOW.plusSeconds(1800));
    }
}

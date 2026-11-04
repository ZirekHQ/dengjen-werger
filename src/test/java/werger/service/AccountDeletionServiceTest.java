package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.UserRepo;
import werger.adapters.db.WorkItemRepo;
import werger.ports.Result;

class AccountDeletionServiceTest {
    private static final UUID USER = UUID.randomUUID();

    private final List<String> calls = new ArrayList<>();

    /** Hand-rolled stubs: the SQL itself, and its races, are proven in the integration tests. */
    private final class StubUsers extends UserRepo {
        final Set<UUID> known;

        StubUsers(Set<UUID> known) {
            super(new JdbcTemplate());
            this.known = known;
        }

        @Override
        public boolean purge(UUID id) {
            calls.add("purge:" + id);
            return known.contains(id);
        }
    }

    private final class StubWorkItems extends WorkItemRepo {
        StubWorkItems() {
            super(new JdbcTemplate());
        }

        @Override
        public int releaseUnfinishedWorkOf(UUID user) {
            calls.add("release:" + user);
            return 0;
        }
    }

    private AccountDeletionService service(Set<UUID> knownUsers) {
        return new AccountDeletionService(new StubUsers(knownUsers), new StubWorkItems(), NoTransactions.template());
    }

    @Test
    void deletingAKnownUserPurgesTheirDetailsAndReleasesTheirUnfinishedWork() {
        var result = service(Set.of(USER)).delete(USER);

        assertThat(result).isEqualTo(Result.ok(null));
        assertThat(calls).containsExactlyInAnyOrder("purge:" + USER, "release:" + USER);
    }

    @Test
    void deletingAnUnknownUserReportsItAndReleasesNothing() {
        var result = service(Set.of()).delete(USER);

        assertThat(result).isEqualTo(Result.err(new DeletionError.UnknownUser()));
        assertThat(calls).containsExactly("purge:" + USER);
    }
}

package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.WorkItemRepo;

class JobsServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    /** Hand-rolled stub: the sweep's SQL and its race with a reclaim are proven in WorkItemRepoIT. */
    private static final class StubRepo extends WorkItemRepo {
        final int reopened;
        Instant sweptAt;

        StubRepo(int reopened) {
            super(new JdbcTemplate());
            this.reopened = reopened;
        }

        @Override
        public int reopenExpiredRevisions(Instant now) {
            sweptAt = now;
            return reopened;
        }
    }

    @Test
    void sweepingRevisionsAsksTheRepoToReopenAnythingPastDueAtTheClockTime() {
        StubRepo repo = new StubRepo(0);

        new JobsService(repo, Clock.fixed(NOW, ZoneOffset.UTC)).sweepExpiredRevisions();

        assertThat(repo.sweptAt).isEqualTo(NOW);
    }

    @Test
    void sweepingRevisionsReportsHowManyItemsWereReopened() {
        StubRepo repo = new StubRepo(3);

        int reopened = new JobsService(repo, Clock.fixed(NOW, ZoneOffset.UTC)).sweepExpiredRevisions();

        assertThat(reopened).isEqualTo(3);
    }
}

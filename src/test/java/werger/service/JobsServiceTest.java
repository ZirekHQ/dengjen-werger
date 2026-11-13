package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Submission;

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

        jobs(repo, List.of(), outcomes -> new ApprovalOutcome.Synced()).sweepExpiredRevisions();

        assertThat(repo.sweptAt).isEqualTo(NOW);
    }

    @Test
    void sweepingRevisionsReportsHowManyItemsWereReopened() {
        StubRepo repo = new StubRepo(3);

        int reopened =
                jobs(repo, List.of(), outcomes -> new ApprovalOutcome.Synced()).sweepExpiredRevisions();

        assertThat(reopened).isEqualTo(3);
    }

    private static JobsService jobs(
            WorkItemRepo repo, List<Submission> owed, Function<Submission, ApprovalOutcome> retry) {
        SubmissionRepo submissions = new SubmissionRepo(new JdbcTemplate()) {
            @Override
            public List<Submission> awaitingPush(String sourceAdapter, Instant now) {
                return owed;
            }
        };
        ApprovalService approvals = new ApprovalService(null, null, null) {
            @Override
            public String adapterName() {
                return "stub";
            }

            @Override
            public ApprovalOutcome retryPush(Submission submission) {
                return retry.apply(submission);
            }
        };
        return new JobsService(repo, submissions, approvals, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void retryingOwedPushesCountsOnlyThosePushedAndRecorded() {
        Submission synced = new Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a", NOW);
        Submission queued = new Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "b", NOW);
        var retry = (Function<Submission, ApprovalOutcome>) submission ->
                submission == synced ? new ApprovalOutcome.Synced() : new ApprovalOutcome.UpstreamPending();

        int pushed = jobs(new StubRepo(0), List.of(synced, queued), retry).retryOwedPushes();

        assertThat(pushed).isEqualTo(1);
    }

    @Test
    void anOwedPushThatThrowsDoesNotStopTheRestOfTheSweep() {
        Submission broken = new Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a", NOW);
        Submission fine = new Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "b", NOW);
        var retry = (Function<Submission, ApprovalOutcome>) submission -> {
            if (submission == broken) {
                throw new IllegalStateException("db down");
            }
            return new ApprovalOutcome.Synced();
        };

        int pushed = jobs(new StubRepo(0), List.of(broken, fine), retry).retryOwedPushes();

        assertThat(pushed).isEqualTo(1);
    }
}

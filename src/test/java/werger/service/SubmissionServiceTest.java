package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Submission;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;
import werger.ports.Result;

class SubmissionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID HOLDER = UUID.randomUUID();
    private static final WorkItem ITEM = new WorkItem(
            UUID.randomUUID(), "crowdin", "1", "kmr", "Hello", Optional.empty(), new WorkItemStatus.Available());

    /** Hand-rolled stubs: the rollback and the lease check are proven against real Postgres in SubmissionServiceIT. */
    private static final class StubWorkItems extends WorkItemRepo {
        boolean holderMatches = true;
        UUID transitionedBy;

        StubWorkItems() {
            super(new JdbcTemplate());
        }

        @Override
        public boolean moveToPendingReview(UUID id, UUID leaseHolder, UUID submissionId) {
            transitionedBy = leaseHolder;
            return holderMatches;
        }
    }

    private static final class StubSubmissions extends SubmissionRepo {
        final List<Submission> inserted = new ArrayList<>();

        StubSubmissions() {
            super(new JdbcTemplate());
        }

        @Override
        public Submission insert(Submission submission) {
            inserted.add(submission);
            return submission;
        }
    }

    private final StubWorkItems workItems = new StubWorkItems();
    private final StubSubmissions submissions = new StubSubmissions();
    private final SubmissionService service =
            new SubmissionService(workItems, submissions, NoTransactions.template(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void submitStoresTheTranslationStampedWithTheClock() {
        var result = service.submit(ITEM, HOLDER, "Silav");

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(submissions.inserted).singleElement().satisfies(stored -> {
            assertThat(stored.proposedTranslation()).isEqualTo("Silav");
            assertThat(stored.submittedAt()).isEqualTo(NOW);
            assertThat(stored.workItem()).isEqualTo(ITEM.id());
        });
        assertThat(workItems.transitionedBy).isEqualTo(HOLDER);
    }

    @Test
    void submitReportsNotLeaseHolderWhenTheTransitionIsRefused() {
        workItems.holderMatches = false;

        assertThat(service.submit(ITEM, HOLDER, "Silav"))
                .isInstanceOfSatisfying(
                        Result.Err.class,
                        err -> assertThat(err.error()).isInstanceOf(SubmitError.NotLeaseHolder.class));
    }
}

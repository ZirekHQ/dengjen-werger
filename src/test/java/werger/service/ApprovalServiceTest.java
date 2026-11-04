package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.ReviewRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Language;
import werger.domain.Review;
import werger.domain.ReviewVerdict;
import werger.domain.Role;
import werger.domain.Submission;
import werger.domain.TmSegment;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;
import werger.ports.Result;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

class ApprovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final WorkItem ITEM = new WorkItem(
            UUID.randomUUID(), "crowdin", "ext-1", "kmr", "Cancel", Optional.empty(), new WorkItemStatus.Approved());
    private static final Submission SUBMISSION =
            new Submission(UUID.randomUUID(), ITEM.id(), UUID.randomUUID(), "Betal", NOW);

    private static final class StubSource implements TranslationSource {
        final List<String> pushed = new ArrayList<>();
        Result<SourceError, Void> outcome = Result.ok(null);

        @Override
        public List<WorkItem> fetchWorkItems(Language language) {
            return List.of();
        }

        @Override
        public List<TmSegment> fetchMemory(Language language) {
            return List.of();
        }

        @Override
        public Result<SourceError, Void> submit(WorkItem item, String translation) {
            pushed.add(item.externalId() + "=" + translation);
            return outcome;
        }
    }

    private static final class StubWorkItems extends WorkItemRepo {
        final List<String> calls = new ArrayList<>();
        boolean transitionMatches = true;

        StubWorkItems() {
            super(new JdbcTemplate());
        }

        @Override
        public Optional<WorkItem> find(UUID id) {
            return Optional.of(ITEM);
        }

        @Override
        public boolean markSynced(UUID id) {
            calls.add("synced");
            return transitionMatches;
        }

        @Override
        public boolean markUpstreamApprovalPending(UUID id) {
            calls.add("upstream_pending");
            return transitionMatches;
        }

        @Override
        public boolean markAwaitingCountersign(UUID id) {
            calls.add("awaiting_countersign");
            return transitionMatches;
        }
    }

    private static final class StubReviews extends ReviewRepo {
        List<Review> outcomes = List.of();

        StubReviews() {
            super(new JdbcTemplate());
        }

        @Override
        public List<Review> decidedOutcomesOf(UUID submitter, int limit) {
            return outcomes;
        }
    }

    private final StubSource source = new StubSource();
    private final StubWorkItems workItems = new StubWorkItems();
    private final StubReviews reviews = new StubReviews();
    private final ApprovalService service = new ApprovalService(source, workItems, reviews);

    private static List<Review> approvals(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Review(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ReviewVerdict.APPROVED,
                        Optional.empty(),
                        NOW.plusSeconds(i)))
                .toList();
    }

    @Test
    void anUntrustedReviewersApprovalIsNotPushedAndWaitsForACountersignInItsOwnStatus() {
        reviews.outcomes = approvals(2);

        var outcome = service.onApproved(SUBMISSION, REVIEWER, Role.CONTRIBUTOR);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.AwaitingCountersign());
        assertThat(source.pushed).isEmpty();
        assertThat(workItems.calls).containsExactly("awaiting_countersign");
    }

    @Test
    void anUntrustedApprovalWhoseTransitionMatchesNoRowReportsSuperseded() {
        reviews.outcomes = approvals(2);
        workItems.transitionMatches = false;

        assertThat(service.onApproved(SUBMISSION, REVIEWER, Role.CONTRIBUTOR))
                .isEqualTo(new ApprovalOutcome.Superseded());
    }

    @Test
    void aMaintainerApprovalIsPushedAndMarksTheItemSynced() {
        var outcome = service.onApproved(SUBMISSION, REVIEWER, Role.MAINTAINER);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.Synced());
        assertThat(source.pushed).containsExactly("ext-1=Betal");
        assertThat(workItems.calls).containsExactly("synced");
    }

    @Test
    void aContributorWithAnEarnedRecordIsTrustedToPush() {
        reviews.outcomes = approvals(TrustLevel.WINDOW);

        assertThat(service.onApproved(SUBMISSION, REVIEWER, Role.CONTRIBUTOR)).isEqualTo(new ApprovalOutcome.Synced());
    }

    @Test
    void aFailedPushLeavesTheItemUpstreamApprovalPendingForRetry() {
        source.outcome = Result.err(new SourceError.Transient("503"));

        var outcome = service.onApproved(SUBMISSION, REVIEWER, Role.MAINTAINER);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.UpstreamPending());
        assertThat(workItems.calls).containsExactly("upstream_pending");
    }

    @Test
    void aPermanentRejectionIsSurfacedWithItsMessageAndNeverQueuedForRetry() {
        source.outcome = Result.err(new SourceError.Rejected("Crowdin string id is not numeric: abc"));

        var outcome = service.onApproved(SUBMISSION, REVIEWER, Role.MAINTAINER);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.UpstreamRejected("Crowdin string id is not numeric: abc"));
        assertThat(workItems.calls).isEmpty();
    }

    @Test
    void aSyncThatMatchesNoRowReportsSupersededInsteadOfSynced() {
        workItems.transitionMatches = false;

        assertThat(service.onApproved(SUBMISSION, REVIEWER, Role.MAINTAINER))
                .isEqualTo(new ApprovalOutcome.Superseded());
    }

    @Test
    void aQueueUpdateThatMatchesNoRowReportsSupersededInsteadOfUpstreamPending() {
        workItems.transitionMatches = false;
        source.outcome = Result.err(new SourceError.Transient("503"));

        assertThat(service.onApproved(SUBMISSION, REVIEWER, Role.MAINTAINER))
                .isEqualTo(new ApprovalOutcome.Superseded());
    }
}

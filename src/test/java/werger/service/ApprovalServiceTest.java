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
import werger.adapters.db.WorkItemRepo;
import werger.domain.Language;
import werger.domain.Submission;
import werger.domain.TmSegment;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;
import werger.ports.Result;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

class ApprovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final WorkItem ITEM = new WorkItem(
            UUID.randomUUID(), "crowdin", "ext-1", "kmr", "Cancel", Optional.empty(), new WorkItemStatus.Approved());
    private static final Submission SUBMISSION =
            new Submission(UUID.randomUUID(), ITEM.id(), UUID.randomUUID(), "Betal", NOW);

    private static final class StubSource implements TranslationSource {
        final List<String> pushed = new ArrayList<>();
        Result<SourceError, Void> outcome = Result.ok(null);

        @Override
        public String adapterName() {
            return "stub";
        }

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
        boolean claimable = true;
        Instant claimedUntil;

        StubWorkItems() {
            super(new JdbcTemplate());
        }

        @Override
        public Optional<WorkItem> find(UUID id) {
            return Optional.of(ITEM);
        }

        @Override
        public boolean claimPush(UUID id, Instant now, Instant until) {
            claimedUntil = until;
            return claimable;
        }

        @Override
        public boolean markSynced(UUID id, UUID submissionId) {
            calls.add("synced");
            return transitionMatches;
        }

        @Override
        public boolean markUpstreamRejected(UUID id) {
            calls.add("upstream_rejected");
            return transitionMatches;
        }

        @Override
        public boolean markUpstreamApprovalPending(UUID id) {
            calls.add("upstream_pending");
            return transitionMatches;
        }
    }

    private final StubSource source = new StubSource();
    private final StubWorkItems workItems = new StubWorkItems();
    private final ApprovalService service = new ApprovalService(source, workItems, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aTrustedApprovalIsPushedAndMarksTheItemSynced() {
        var outcome = service.onApproved(SUBMISSION);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.Synced());
        assertThat(source.pushed).containsExactly("ext-1=Betal");
        assertThat(workItems.calls).containsExactly("synced");
    }

    @Test
    void aFailedPushLeavesTheItemUpstreamApprovalPendingForRetry() {
        source.outcome = Result.err(new SourceError.Transient("503"));

        var outcome = service.onApproved(SUBMISSION);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.UpstreamPending());
        assertThat(workItems.calls).containsExactly("upstream_pending");
    }

    @Test
    void aPermanentRejectionIsSurfacedWithItsMessageAndParkedSoItIsNeverRetried() {
        source.outcome = Result.err(new SourceError.Rejected("Crowdin string id is not numeric: abc"));

        var outcome = service.onApproved(SUBMISSION);

        assertThat(outcome).isEqualTo(new ApprovalOutcome.UpstreamRejected("Crowdin string id is not numeric: abc"));
        assertThat(workItems.calls).containsExactly("upstream_rejected");
    }

    @Test
    void aRejectionRecordThatMatchesNoRowReportsSuperseded() {
        workItems.transitionMatches = false;
        source.outcome = Result.err(new SourceError.Rejected("plural"));

        assertThat(service.onApproved(SUBMISSION)).isEqualTo(new ApprovalOutcome.Superseded());
    }

    @Test
    void aSyncThatMatchesNoRowReportsSupersededInsteadOfSynced() {
        workItems.transitionMatches = false;

        assertThat(service.onApproved(SUBMISSION)).isEqualTo(new ApprovalOutcome.Superseded());
    }

    @Test
    void aQueueUpdateThatMatchesNoRowReportsSupersededInsteadOfUpstreamPending() {
        workItems.transitionMatches = false;
        source.outcome = Result.err(new SourceError.Transient("503"));

        assertThat(service.onApproved(SUBMISSION)).isEqualTo(new ApprovalOutcome.Superseded());
    }

    @Test
    void anItemWhosePushIsClaimedElsewhereIsNotPushedAgain() {
        workItems.claimable = false;

        assertThat(service.onApproved(SUBMISSION)).isEqualTo(new ApprovalOutcome.PushInFlight());
        assertThat(source.pushed).isEmpty();
        assertThat(workItems.calls).isEmpty();
    }

    @Test
    void aPushHoldsItsClaimForTheClaimDurationFromTheClock() {
        service.onApproved(SUBMISSION);

        assertThat(workItems.claimedUntil).isEqualTo(NOW.plus(ApprovalService.PUSH_CLAIM));
    }
}

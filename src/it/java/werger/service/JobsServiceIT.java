package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.adapters.db.ItFixture;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Language;
import werger.domain.Submission;
import werger.domain.TmSegment;
import werger.domain.WorkItem;
import werger.ports.Result;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

class JobsServiceIT {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private final String adapter = "jobs-it-" + UUID.randomUUID();
    private ItFixture db;
    private WorkItemRepo workItems;
    private JobsService jobs;
    private final List<String> pushed = new ArrayList<>();
    private Result<SourceError, Void> pushResult = Result.ok(null);
    private Runnable duringPush = () -> {};

    @BeforeEach
    void connect() {
        db = ItFixture.open(adapter);
        workItems = new WorkItemRepo(db.jdbc());
        SubmissionRepo submissions = new SubmissionRepo(db.jdbc());
        jobs = new JobsService(
                workItems,
                submissions,
                new ApprovalService(new RecordingSource(), workItems, Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private final class RecordingSource implements TranslationSource {
        @Override
        public String adapterName() {
            return adapter;
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
            pushed.add(item.id().toString());
            duringPush.run();
            return pushResult;
        }
    }

    private Submission approvedAfterACrash() {
        Submission submission = db.pendingSubmission(db.newUser(), NOW);
        workItems.markApproved(submission.workItem(), submission.id());
        return submission;
    }

    private void claimThenQueue(Submission submission) {
        UUID claim = UUID.randomUUID();
        workItems.claimPush(submission.workItem(), NOW, NOW.plusSeconds(60), claim);
        workItems.markUpstreamApprovalPending(submission.workItem(), submission.id(), claim);
    }

    @Test
    void anApprovalThatCrashedBeforeItsPushIsPushedAndSynced() {
        Submission stuck = approvedAfterACrash();

        assertThat(jobs.retryOwedPushes()).isEqualTo(1);

        assertThat(pushed).containsExactly(stuck.workItem().toString());
        assertThat(db.statusOf(stuck.workItem())).isEqualTo("synced");
    }

    @Test
    void anItemQueuedAfterATransientFailureIsRetriedAndSynced() {
        Submission queued = approvedAfterACrash();
        claimThenQueue(queued);

        assertThat(jobs.retryOwedPushes()).isEqualTo(1);

        assertThat(db.statusOf(queued.workItem())).isEqualTo("synced");
    }

    @Test
    void anUntrustedApprovalAwaitingCountersignIsNeverPushed() {
        Submission waiting = db.pendingSubmission(db.newUser(), NOW);
        workItems.markApprovedAwaitingCountersign(waiting.workItem(), waiting.id());

        assertThat(jobs.retryOwedPushes()).isZero();

        assertThat(pushed).isEmpty();
        assertThat(db.statusOf(waiting.workItem())).isEqualTo("awaiting_countersign");
    }

    @Test
    void aPushThatFailsAgainLeavesTheItemQueuedForTheNextSweep() {
        Submission stuck = approvedAfterACrash();
        pushResult = Result.err(new SourceError.Transient("down"));

        assertThat(jobs.retryOwedPushes()).isZero();

        assertThat(db.statusOf(stuck.workItem())).isEqualTo("upstream_approval_pending");
    }

    @Test
    void anAlreadySyncedItemIsNotPushedAgain() {
        Submission done = approvedAfterACrash();
        workItems.markSynced(done.workItem(), done.id());

        assertThat(jobs.retryOwedPushes()).isZero();

        assertThat(pushed).isEmpty();
    }

    @Test
    void anApprovedItemOfAnotherAdapterIsNeitherPushedNorTouched() {
        try (ItFixture other = ItFixture.open(adapter + "-other")) {
            Submission foreign = other.pendingSubmission(other.newUser(), NOW);
            workItems.markApproved(foreign.workItem(), foreign.id());

            assertThat(jobs.retryOwedPushes()).isZero();

            assertThat(pushed).isEmpty();
            assertThat(other.statusOf(foreign.workItem())).isEqualTo("approved");
        }
    }

    @Test
    void aPermanentlyRejectedPushIsNotRetriedOnTheNextSweep() {
        Submission refused = approvedAfterACrash();
        pushResult = Result.err(new SourceError.Rejected("plural"));

        jobs.retryOwedPushes();
        jobs.retryOwedPushes();

        assertThat(pushed).containsExactly(refused.workItem().toString());
        assertThat(db.statusOf(refused.workItem())).isEqualTo("upstream_rejected");
    }

    @Test
    void aPushThatOutlivesItsClaimNeitherReleasesNorOverwritesTheNewHoldersClaim() {
        Submission slow = approvedAfterACrash();
        Instant afterLapse = NOW.plus(ApprovalService.PUSH_CLAIM).plusSeconds(1);
        pushResult = Result.err(new SourceError.Transient("hung"));
        duringPush = () -> assertThat(workItems.claimPush(
                        slow.workItem(), afterLapse, afterLapse.plusSeconds(300), UUID.randomUUID()))
                .isTrue();

        assertThat(jobs.retryOwedPushes()).isZero();

        assertThat(db.statusOf(slow.workItem())).isEqualTo("approved");
        assertThat(workItems.claimPush(
                        slow.workItem(), afterLapse.plusSeconds(1), afterLapse.plusSeconds(400), UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void anItemWhosePushIsInFlightIsLeftToThatPush() {
        Submission inFlight = approvedAfterACrash();
        workItems.claimPush(inFlight.workItem(), NOW, NOW.plusSeconds(60), UUID.randomUUID());

        assertThat(jobs.retryOwedPushes()).isZero();

        assertThat(pushed).isEmpty();
        assertThat(db.statusOf(inFlight.workItem())).isEqualTo("approved");
    }

    @Test
    void anItemWhoseClaimLapsedAfterACrashIsPushedAgain() {
        Submission lapsed = approvedAfterACrash();
        workItems.claimPush(lapsed.workItem(), NOW.minusSeconds(600), NOW.minusSeconds(1), UUID.randomUUID());

        assertThat(jobs.retryOwedPushes()).isEqualTo(1);

        assertThat(db.statusOf(lapsed.workItem())).isEqualTo("synced");
    }
}

package werger.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Submission;
import werger.domain.WorkItem;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

/** Pushes a trusted approval upstream and records the result. */
public class ApprovalService {
    static final Duration PUSH_CLAIM = Duration.ofMinutes(5);

    private final TranslationSource source;
    private final WorkItemRepo workItems;
    private final Clock clock;

    public ApprovalService(TranslationSource source, WorkItemRepo workItems, Clock clock) {
        this.source = source;
        this.workItems = workItems;
        this.clock = clock;
    }

    public String adapterName() {
        return source.adapterName();
    }

    /** Must run after the approving review has committed, so a slow upstream never holds its transaction open. */
    public ApprovalOutcome onApproved(Submission submission) {
        return push(submission);
    }

    /** Pushes an approval whose earlier push never recorded a result. The item's status already holds the trust decision. */
    public ApprovalOutcome retryPush(Submission submission) {
        return push(submission);
    }

    private ApprovalOutcome push(Submission submission) {
        Instant now = clock.instant();
        UUID claimId = UUID.randomUUID();
        return workItems.claimPush(submission.workItem(), now, now.plus(PUSH_CLAIM), claimId)
                ? pushClaimed(submission, claimId)
                : new ApprovalOutcome.PushInFlight();
    }

    private ApprovalOutcome pushClaimed(Submission submission, UUID claimId) {
        WorkItem item = workItems
                .find(submission.workItem())
                .orElseThrow(() -> new IllegalStateException("Approved item vanished: " + submission.workItem()));
        return source.submit(item, submission.proposedTranslation())
                .<ApprovalOutcome>fold(
                        error -> onPushFailed(item, submission, claimId, error), ok -> markSynced(submission));
    }

    private ApprovalOutcome markSynced(Submission submission) {
        return recorded(workItems.markSynced(submission.workItem(), submission.id()), new ApprovalOutcome.Synced());
    }

    private ApprovalOutcome onPushFailed(WorkItem item, Submission submission, UUID claimId, SourceError error) {
        return switch (error) {
            case SourceError.Transient _ ->
                recorded(
                        workItems.markUpstreamApprovalPending(item.id(), submission.id(), claimId),
                        new ApprovalOutcome.UpstreamPending());
            case SourceError.Rejected(String message) ->
                recorded(
                        workItems.markUpstreamRejected(item.id(), submission.id(), claimId),
                        new ApprovalOutcome.UpstreamRejected(message));
        };
    }

    private static ApprovalOutcome recorded(boolean moved, ApprovalOutcome outcome) {
        return moved ? outcome : new ApprovalOutcome.Superseded();
    }
}

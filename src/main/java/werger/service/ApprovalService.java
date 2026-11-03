package werger.service;

import java.util.UUID;
import werger.adapters.db.ReviewRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Role;
import werger.domain.Submission;
import werger.domain.WorkItem;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

/** Pushes an approved translation upstream when the reviewer is trusted to, and queues it otherwise. */
public class ApprovalService {
    private final TranslationSource source;
    private final WorkItemRepo workItems;
    private final ReviewRepo reviews;

    public ApprovalService(TranslationSource source, WorkItemRepo workItems, ReviewRepo reviews) {
        this.source = source;
        this.workItems = workItems;
        this.reviews = reviews;
    }

    /** Must run after the approving review has committed, so a slow upstream never holds its transaction open. */
    public ApprovalOutcome onApproved(Submission submission, UUID reviewer, Role reviewerRole) {
        if (!TrustLevel.isTrusted(reviewerRole, reviews.decidedOutcomesOf(reviewer, TrustLevel.WINDOW))) {
            return new ApprovalOutcome.AwaitingCountersign();
        }
        return push(submission);
    }

    private ApprovalOutcome push(Submission submission) {
        WorkItem item = workItems
                .find(submission.workItem())
                .orElseThrow(() -> new IllegalStateException("Approved item vanished: " + submission.workItem()));
        return source.submit(item, submission.proposedTranslation())
                .<ApprovalOutcome>fold(error -> onPushFailed(item, error), ok -> markSynced(item));
    }

    private ApprovalOutcome markSynced(WorkItem item) {
        return recorded(workItems.markSynced(item.id()), new ApprovalOutcome.Synced());
    }

    private ApprovalOutcome onPushFailed(WorkItem item, SourceError error) {
        return switch (error) {
            case SourceError.Transient _ ->
                recorded(workItems.markUpstreamApprovalPending(item.id()), new ApprovalOutcome.UpstreamPending());
            case SourceError.Rejected(String message) -> new ApprovalOutcome.UpstreamRejected(message);
        };
    }

    private static ApprovalOutcome recorded(boolean moved, ApprovalOutcome outcome) {
        return moved ? outcome : new ApprovalOutcome.Superseded();
    }
}

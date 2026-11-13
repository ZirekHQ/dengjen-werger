package werger.service;

import werger.adapters.db.WorkItemRepo;
import werger.domain.Submission;
import werger.domain.WorkItem;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

/** Pushes a trusted approval upstream and records the result. */
public class ApprovalService {
    private final TranslationSource source;
    private final WorkItemRepo workItems;

    public ApprovalService(TranslationSource source, WorkItemRepo workItems) {
        this.source = source;
        this.workItems = workItems;
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
        WorkItem item = workItems
                .find(submission.workItem())
                .orElseThrow(() -> new IllegalStateException("Approved item vanished: " + submission.workItem()));
        return source.submit(item, submission.proposedTranslation())
                .<ApprovalOutcome>fold(error -> onPushFailed(item, error), ok -> markSynced(submission));
    }

    private ApprovalOutcome markSynced(Submission submission) {
        return recorded(workItems.markSynced(submission.workItem(), submission.id()), new ApprovalOutcome.Synced());
    }

    private ApprovalOutcome onPushFailed(WorkItem item, SourceError error) {
        return switch (error) {
            case SourceError.Transient _ ->
                recorded(workItems.markUpstreamApprovalPending(item.id()), new ApprovalOutcome.UpstreamPending());
            case SourceError.Rejected(String message) ->
                recorded(workItems.markUpstreamRejected(item.id()), new ApprovalOutcome.UpstreamRejected(message));
        };
    }

    private static ApprovalOutcome recorded(boolean moved, ApprovalOutcome outcome) {
        return moved ? outcome : new ApprovalOutcome.Superseded();
    }
}

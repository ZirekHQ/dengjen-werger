package werger.service;

import java.time.Clock;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;

/** The scheduled housekeeping the platform runs between user actions. */
public class JobsService {
    private final WorkItemRepo workItems;
    private final SubmissionRepo submissions;
    private final ApprovalService approvals;
    private final Clock clock;

    public JobsService(WorkItemRepo workItems, SubmissionRepo submissions, ApprovalService approvals, Clock clock) {
        this.workItems = workItems;
        this.submissions = submissions;
        this.approvals = approvals;
        this.clock = clock;
    }

    /** Returns rejected items to the pool once their submitter's revision window has closed, and counts them. */
    public int sweepExpiredRevisions() {
        return workItems.reopenExpiredRevisions(clock.instant());
    }

    /**
     * Pushes every approval of this service's source adapter whose push is still owed after a crash or a transient failure, and counts those that
     * synced. Untrusted approvals sit in {@code awaiting_countersign}, so the sweep never sees them.
     */
    public int retryOwedPushes() {
        return (int) submissions.awaitingPush(approvals.adapterName()).stream()
                .map(approvals::retryPush)
                .filter(new ApprovalOutcome.Synced()::equals)
                .count();
    }
}

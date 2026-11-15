package werger.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Submission;

/** The scheduled housekeeping the platform runs between user actions. */
public class JobsService {
    private static final Logger LOG = LoggerFactory.getLogger(JobsService.class);

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
     * Pushes every approval of this service's source adapter whose push is owed and not in flight, and counts those that
     * synced. A push that throws is logged and left for the next sweep; it never stops the rest. Untrusted approvals sit
     * in {@code awaiting_countersign}, so the sweep never sees them.
     */
    public int retryOwedPushes() {
        return (int) submissions.awaitingPush(approvals.adapterName(), clock.instant()).stream()
                .filter(this::retriedToSynced)
                .count();
    }

    private boolean retriedToSynced(Submission submission) {
        try {
            return new ApprovalOutcome.Synced().equals(approvals.retryPush(submission));
        } catch (RuntimeException e) {
            LOG.error("Owed push failed for work item {}; the next sweep retries it", submission.workItem(), e);
            return false;
        }
    }
}

package werger.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Submission;
import werger.domain.WorkItem;
import werger.ports.Result;

/** Turns a lease holder's translation into a submission awaiting review. */
public class SubmissionService {
    private final WorkItemRepo workItems;
    private final SubmissionRepo submissions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SubmissionService(
            WorkItemRepo workItems, SubmissionRepo submissions, TransactionTemplate transactions, Clock clock) {
        this.workItems = workItems;
        this.submissions = submissions;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Result<SubmitError, Submission> submit(WorkItem item, UUID submitter, String text) {
        Submission submission = new Submission(UUID.randomUUID(), item.id(), submitter, text, clock.instant());
        return Objects.requireNonNull(transactions.execute(status -> attempt(status, submission)));
    }

    // The submission row must exist before the item can point at it, so a refused transition rolls the row back.
    private Result<SubmitError, Submission> attempt(TransactionStatus status, Submission submission) {
        submissions.insert(submission);
        if (workItems.moveToPendingReview(submission.workItem(), submission.submitter(), submission.id())) {
            return Result.ok(submission);
        }
        status.setRollbackOnly();
        return Result.err(new SubmitError.NotLeaseHolder());
    }
}

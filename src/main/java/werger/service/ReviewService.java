package werger.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import werger.adapters.db.PointsRepo;
import werger.adapters.db.ReviewRepo;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.PointsLedgerEntry;
import werger.domain.PointsReason;
import werger.domain.Review;
import werger.domain.ReviewCandidate;
import werger.domain.ReviewVerdict;
import werger.domain.Role;
import werger.domain.Submission;
import werger.ports.Result;

/** Serves reviewers the oldest pending submission and records their verdict. */
public class ReviewService {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewService.class);
    static final Duration REVISION_WINDOW = Duration.ofHours(48);
    static final int REVIEW_POINTS = 5;
    static final int APPROVAL_POINTS = 10;

    private final WorkItemRepo workItems;
    private final SubmissionRepo submissions;
    private final ReviewRepo reviews;
    private final PointsRepo points;
    private final ApprovalService approvals;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReviewService(
            WorkItemRepo workItems,
            SubmissionRepo submissions,
            ReviewRepo reviews,
            PointsRepo points,
            ApprovalService approvals,
            TransactionTemplate transactions,
            Clock clock) {
        this.workItems = workItems;
        this.submissions = submissions;
        this.reviews = reviews;
        this.points = points;
        this.approvals = approvals;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Optional<ReviewCandidate> nextForReview(UUID reviewerId) {
        return submissions.oldestPendingFor(reviewerId);
    }

    /**
     * Records the verdict and, for a trusted approval, pushes it once the review has committed. An untrusted approval
     * commits straight into {@code awaiting_countersign}. The caller must already have checked that {@code reviewer} is
     * an active user and supplies their role.
     */
    public Result<ReviewError, Optional<ApprovalOutcome>> review(
            Submission submission, UUID reviewer, Role reviewerRole, ReviewVerdict verdict, Optional<String> comment) {
        if (submission.submitter().equals(reviewer)) {
            return Result.err(new ReviewError.OwnSubmission());
        }
        Review review = new Review(submission.id(), reviewer, verdict, comment, clock.instant());
        boolean trusted = verdict == ReviewVerdict.APPROVED && isTrusted(reviewer, reviewerRole);
        return Objects.requireNonNull(transactions.execute(status -> decide(status, submission, review, trusted)))
                .fold(Result::err, ok -> Result.ok(approve(submission, review, trusted)));
    }

    private boolean isTrusted(UUID reviewer, Role reviewerRole) {
        return TrustLevel.isTrusted(reviewerRole, reviews.decidedOutcomesOf(reviewer, TrustLevel.WINDOW));
    }

    private Optional<ApprovalOutcome> approve(Submission submission, Review review, boolean trusted) {
        if (review.verdict() != ReviewVerdict.APPROVED) {
            return Optional.empty();
        }
        return Optional.of(trusted ? push(submission) : new ApprovalOutcome.AwaitingCountersign());
    }

    // The review has committed, so a failure here must not read as a failed review; the sweep owns the retry.
    private ApprovalOutcome push(Submission submission) {
        try {
            return approvals.onApproved(submission);
        } catch (RuntimeException e) {
            LOG.error("Push failed after review of submission {}; the sweep retries it", submission.id(), e);
            return new ApprovalOutcome.PushDeferred();
        }
    }

    // The status-gated transition runs first so that of two racing reviewers only one reaches the review insert.
    private Result<ReviewError, Void> decide(
            TransactionStatus status, Submission submission, Review review, boolean trusted) {
        if (!transition(submission, review, trusted)) {
            status.setRollbackOnly();
            return Result.err(new ReviewError.AlreadyDecided());
        }
        reviews.insert(review);
        payOut(submission, review);
        return Result.ok(null);
    }

    // Runs in the same transaction as the transition, so a rollback also voids the payout.
    private void payOut(Submission submission, Review review) {
        award(review.reviewer(), submission, PointsReason.REVIEW_COMPLETED, REVIEW_POINTS, review);
        if (review.verdict() == ReviewVerdict.APPROVED) {
            award(submission.submitter(), submission, PointsReason.SUBMISSION_APPROVED, APPROVAL_POINTS, review);
        }
    }

    private void award(UUID user, Submission submission, PointsReason reason, int amount, Review review) {
        points.award(new PointsLedgerEntry(user, submission.id(), reason, amount, review.reviewedAt()));
    }

    private boolean transition(Submission submission, Review review, boolean trusted) {
        return switch (review.verdict()) {
            case APPROVED -> approvedStatus(submission, trusted);
            case REJECTED ->
                workItems.moveToRevisionPending(
                        submission.workItem(),
                        submission.id(),
                        submission.submitter(),
                        review.comment(),
                        review.reviewedAt().plus(REVISION_WINDOW));
        };
    }

    private boolean approvedStatus(Submission submission, boolean trusted) {
        return trusted
                ? workItems.markApproved(submission.workItem(), submission.id())
                : workItems.markApprovedAwaitingCountersign(submission.workItem(), submission.id());
    }
}

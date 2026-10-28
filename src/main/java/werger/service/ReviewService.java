package werger.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import werger.domain.Submission;
import werger.ports.Result;

/** Serves reviewers the oldest pending submission and records their verdict. */
public class ReviewService {
    static final Duration REVISION_WINDOW = Duration.ofHours(48);
    static final int REVIEW_POINTS = 5;
    static final int APPROVAL_POINTS = 10;

    private final WorkItemRepo workItems;
    private final SubmissionRepo submissions;
    private final ReviewRepo reviews;
    private final PointsRepo points;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReviewService(
            WorkItemRepo workItems,
            SubmissionRepo submissions,
            ReviewRepo reviews,
            PointsRepo points,
            TransactionTemplate transactions,
            Clock clock) {
        this.workItems = workItems;
        this.submissions = submissions;
        this.reviews = reviews;
        this.points = points;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Optional<ReviewCandidate> nextForReview(UUID reviewerId) {
        return submissions.oldestPendingFor(reviewerId);
    }

    /** Records the verdict. The caller must already have checked that {@code reviewer} is an active user. */
    public Result<ReviewError, Void> review(
            Submission submission, UUID reviewer, ReviewVerdict verdict, Optional<String> comment) {
        if (submission.submitter().equals(reviewer)) {
            return Result.err(new ReviewError.OwnSubmission());
        }
        Review review = new Review(submission.id(), reviewer, verdict, comment, clock.instant());
        return Objects.requireNonNull(transactions.execute(status -> decide(status, submission, review)));
    }

    // The status-gated transition runs first so that of two racing reviewers only one reaches the review insert.
    private Result<ReviewError, Void> decide(TransactionStatus status, Submission submission, Review review) {
        if (!transition(submission, review)) {
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

    private boolean transition(Submission submission, Review review) {
        return switch (review.verdict()) {
            case APPROVED -> workItems.markApproved(submission.workItem(), submission.id());
            case REJECTED ->
                workItems.moveToRevisionPending(
                        submission.workItem(),
                        submission.id(),
                        submission.submitter(),
                        review.comment(),
                        review.reviewedAt().plus(REVISION_WINDOW));
        };
    }
}

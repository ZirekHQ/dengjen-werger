package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.adapters.db.ItFixture;
import werger.adapters.db.PointsRepo;
import werger.adapters.db.ReviewRepo;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.ReviewVerdict;
import werger.domain.Submission;
import werger.domain.WorkItemStatus;
import werger.ports.Result;

class ReviewServiceIT {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private ItFixture db;
    private WorkItemRepo workItems;
    private ReviewService service;

    @BeforeEach
    void connect() {
        db = ItFixture.open("review-service-it");
        workItems = new WorkItemRepo(db.jdbc());
        service = new ReviewService(
                workItems,
                new SubmissionRepo(db.jdbc()),
                new ReviewRepo(db.jdbc()),
                new PointsRepo(db.jdbc()),
                db.transactions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private int reviewCount(Submission submission) {
        return db.jdbc()
                .queryForObject("select count(*) from reviews where submission_id = ?", Integer.class, submission.id());
    }

    @Test
    void approvalMarksTheItemApproved() {
        Submission submission = db.pendingSubmission(db.newUser(), NOW);

        var result = service.review(submission, db.newUser(), ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(db.statusOf(submission.workItem())).isEqualTo("approved");
        assertThat(reviewCount(submission)).isEqualTo(1);
    }

    @Test
    void rejectionMovesTheItemToRevisionPendingForTheSubmitter() {
        UUID submitter = db.newUser();
        Submission submission = db.pendingSubmission(submitter, NOW);

        service.review(submission, db.newUser(), ReviewVerdict.REJECTED, Optional.of("wrong register"));

        assertThat(workItems.find(submission.workItem()).orElseThrow().status())
                .isEqualTo(
                        new WorkItemStatus.RevisionPending(submitter, "wrong register", NOW.plusSeconds(48 * 3_600)));
    }

    @Test
    void rejectionWithoutACommentStillReachesRevisionPending() {
        Submission submission = db.pendingSubmission(db.newUser(), NOW);

        service.review(submission, db.newUser(), ReviewVerdict.REJECTED, Optional.empty());

        assertThat(db.statusOf(submission.workItem())).isEqualTo("revision_pending");
    }

    @Test
    void aSubmissionThatIsNotTheItemsCurrentOneCannotDecideIt() {
        Submission current = db.pendingSubmission(db.newUser(), NOW);
        Submission stale =
                new Submission(UUID.randomUUID(), current.workItem(), current.submitter(), "old", NOW.minusSeconds(60));
        new SubmissionRepo(db.jdbc()).insert(stale);

        var result = service.review(stale, db.newUser(), ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class,
                        err -> assertThat(err.error()).isInstanceOf(ReviewError.AlreadyDecided.class));
        assertThat(db.statusOf(current.workItem())).isEqualTo("pending_review");
        assertThat(reviewCount(stale)).isZero();
    }

    @Test
    void nextForReviewServesTheOldestSubmissionNotAChosenOne() {
        UUID submitter = db.newUser();
        db.pendingSubmission(submitter, NOW.plusSeconds(60));
        Submission oldest = db.pendingSubmission(submitter, NOW);

        assertThat(service.nextForReview(db.newUser()))
                .map(candidate -> candidate.submission().id())
                .contains(oldest.id());
    }

    @Test
    void simultaneousReviewsOfOneSubmissionHaveExactlyOneWinner() throws Exception {
        Submission submission = db.pendingSubmission(db.newUser(), NOW);
        List<UUID> reviewers = IntStream.range(0, 8).mapToObj(i -> db.newUser()).toList();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> attempts = IntStream.range(0, reviewers.size())
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        start.await();
                        return service.review(submission, reviewers.get(i), verdictFor(i), Optional.empty())
                                instanceof Result.Ok<?, ?>;
                    })
                    .toList();
            List<Future<Boolean>> futures =
                    attempts.stream().map(executor::submit).toList();
            start.countDown();
            long winners = futures.stream().filter(ReviewServiceIT::get).count();

            assertThat(winners).isEqualTo(1);
            assertThat(reviewCount(submission)).isEqualTo(1);
            int expectedRows = db.statusOf(submission.workItem()).equals("approved") ? 2 : 1;
            assertThat(ledgerRows(submission)).isEqualTo(expectedRows);
        }
    }

    private int ledgerRows(Submission submission) {
        return db.jdbc()
                .queryForObject(
                        "select count(*) from points_ledger where submission_id = ?", Integer.class, submission.id());
    }

    @Test
    void approvalWritesBothPayoutsAndRejectionOnlyTheReviewers() {
        Submission approved = db.pendingSubmission(db.newUser(), NOW);
        Submission rejected = db.pendingSubmission(db.newUser(), NOW);

        service.review(approved, db.newUser(), ReviewVerdict.APPROVED, Optional.empty());
        service.review(rejected, db.newUser(), ReviewVerdict.REJECTED, Optional.empty());

        assertThat(ledgerRows(approved)).isEqualTo(2);
        assertThat(ledgerRows(rejected)).isEqualTo(1);
    }

    @Test
    void aReviewThatLosesTheRacePaysNobody() {
        Submission current = db.pendingSubmission(db.newUser(), NOW);
        Submission stale =
                new Submission(UUID.randomUUID(), current.workItem(), current.submitter(), "old", NOW.minusSeconds(60));
        new SubmissionRepo(db.jdbc()).insert(stale);

        service.review(stale, db.newUser(), ReviewVerdict.APPROVED, Optional.empty());

        assertThat(ledgerRows(stale)).isZero();
    }

    private static ReviewVerdict verdictFor(int index) {
        return index % 2 == 0 ? ReviewVerdict.APPROVED : ReviewVerdict.REJECTED;
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

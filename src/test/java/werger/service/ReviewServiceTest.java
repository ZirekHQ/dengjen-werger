package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.ReviewRepo;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Review;
import werger.domain.ReviewVerdict;
import werger.domain.Submission;
import werger.ports.Result;

class ReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final Submission SUBMISSION =
            new Submission(UUID.randomUUID(), UUID.randomUUID(), SUBMITTER, "x", NOW);

    /** Hand-rolled stubs: the transitions and the race are proven against real Postgres in ReviewServiceIT. */
    private static final class StubWorkItems extends WorkItemRepo {
        final List<String> calls = new ArrayList<>();
        boolean transitionWins = true;
        Instant revisionDeadline;
        Optional<String> revisionComment = Optional.empty();

        StubWorkItems() {
            super(new JdbcTemplate());
        }

        @Override
        public boolean markApproved(UUID id, UUID submissionId) {
            calls.add("approved");
            return transitionWins;
        }

        @Override
        public boolean moveToRevisionPending(
                UUID id, UUID submissionId, UUID submitter, Optional<String> comment, Instant expiresAt) {
            calls.add("revision:" + submitter);
            revisionDeadline = expiresAt;
            revisionComment = comment;
            return transitionWins;
        }
    }

    private static final class StubReviews extends ReviewRepo {
        final List<Review> inserted = new ArrayList<>();

        StubReviews() {
            super(new JdbcTemplate());
        }

        @Override
        public void insert(Review review) {
            inserted.add(review);
        }
    }

    private final StubWorkItems workItems = new StubWorkItems();
    private final StubReviews reviews = new StubReviews();
    private final ReviewService service = new ReviewService(
            workItems,
            new SubmissionRepo(new JdbcTemplate()),
            reviews,
            NoTransactions.template(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reviewerCannotReviewTheirOwnSubmissionAndNothingIsWritten() {
        var result = service.review(SUBMISSION, SUBMITTER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(ReviewError.OwnSubmission.class));
        assertThat(workItems.calls).isEmpty();
        assertThat(reviews.inserted).isEmpty();
    }

    @Test
    void approvalMarksTheItemApprovedAndRecordsTheReview() {
        var result = service.review(SUBMISSION, REVIEWER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(workItems.calls).containsExactly("approved");
        assertThat(reviews.inserted).singleElement().satisfies(review -> {
            assertThat(review.verdict()).isEqualTo(ReviewVerdict.APPROVED);
            assertThat(review.reviewer()).isEqualTo(REVIEWER);
            assertThat(review.reviewedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void rejectionGivesTheSubmitterAFortyEightHourWindowFromTheClock() {
        service.review(SUBMISSION, REVIEWER, ReviewVerdict.REJECTED, Optional.of("wrong register"));

        assertThat(workItems.calls).containsExactly("revision:" + SUBMITTER);
        assertThat(workItems.revisionDeadline).isEqualTo(NOW.plusSeconds(48 * 3_600));
        assertThat(workItems.revisionComment).contains("wrong register");
    }

    @Test
    void aLostRaceReportsAlreadyDecidedAndRecordsNoReview() {
        workItems.transitionWins = false;

        var result = service.review(SUBMISSION, REVIEWER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class,
                        err -> assertThat(err.error()).isInstanceOf(ReviewError.AlreadyDecided.class));
        assertThat(reviews.inserted).isEmpty();
    }
}

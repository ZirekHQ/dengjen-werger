package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import werger.domain.Review;
import werger.domain.ReviewVerdict;

class ReviewRepoIT {
    private ItFixture db;
    private ReviewRepo repo;

    @BeforeEach
    void connect() {
        db = ItFixture.open("review-repo-it");
        repo = new ReviewRepo(db.jdbc());
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void insertStoresTheVerdictAndComment() {
        UUID submission = db.pendingSubmission(db.newUser(), Instant.now()).id();

        repo.insert(new Review(
                submission, db.newUser(), ReviewVerdict.REJECTED, Optional.of("wrong register"), Instant.now()));

        assertThat(db.jdbc()
                        .queryForObject(
                                "select verdict::text || ':' || comment from reviews where submission_id = ?",
                                String.class,
                                submission))
                .isEqualTo("rejected:wrong register");
    }

    @Test
    void insertRejectsTheSubmittersOwnReview() {
        UUID submitter = db.newUser();
        UUID submission = db.pendingSubmission(submitter, Instant.now()).id();
        Review own = new Review(submission, submitter, ReviewVerdict.APPROVED, Optional.empty(), Instant.now());

        assertThatThrownBy(() -> repo.insert(own)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void decidedOutcomesOfReturnsTheNewestReviewsOfTheSubmittersOwnWorkOnly() {
        UUID submitter = db.newUser();
        Instant base = Instant.parse("2026-09-01T12:00:00Z");
        Review oldest = reviewed(submitter, ReviewVerdict.REJECTED, base);
        Review middle = reviewed(submitter, ReviewVerdict.APPROVED, base.plusSeconds(60));
        Review newest = reviewed(submitter, ReviewVerdict.APPROVED, base.plusSeconds(120));
        reviewed(db.newUser(), ReviewVerdict.REJECTED, base.plusSeconds(180));

        assertThat(repo.decidedOutcomesOf(submitter, 2)).containsExactly(newest, middle);
        assertThat(repo.decidedOutcomesOf(submitter, 10)).containsExactly(newest, middle, oldest);
    }

    private Review reviewed(UUID submitter, ReviewVerdict verdict, Instant at) {
        UUID submission = db.pendingSubmission(submitter, at).id();
        Review review = new Review(submission, db.newUser(), verdict, Optional.empty(), at);
        repo.insert(review);
        return review;
    }
}

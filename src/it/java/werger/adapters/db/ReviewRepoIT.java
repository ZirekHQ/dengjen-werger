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
}

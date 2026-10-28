package werger.adapters.db;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.Review;

public class ReviewRepo {
    private final JdbcTemplate jdbc;

    public ReviewRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws if the reviewer submitted the work: the {@code reviews_reviewer_not_submitter} trigger is the backstop. */
    public void insert(Review review) {
        jdbc.update(
                """
                insert into reviews (submission_id, reviewer_id, verdict, comment, reviewed_at)
                values (?, ?, ?::review_verdict, ?, ?)
                """,
                review.submission(),
                review.reviewer(),
                review.verdict().name().toLowerCase(Locale.ROOT),
                review.comment().orElse(null),
                OffsetDateTime.ofInstant(review.reviewedAt(), ZoneOffset.UTC));
    }
}

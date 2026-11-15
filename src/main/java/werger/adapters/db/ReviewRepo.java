package werger.adapters.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.Review;
import werger.domain.ReviewVerdict;

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

    /** The newest {@code limit} reviews of submissions that {@code submitter} wrote, which is their approval record. */
    public List<Review> decidedOutcomesOf(UUID submitter, int limit) {
        return jdbc.query("""
                select r.submission_id, r.reviewer_id, r.verdict, r.comment, r.reviewed_at
                from reviews r
                join submissions s on s.id = r.submission_id
                where s.submitter_id = ?
                order by r.reviewed_at desc, r.id
                limit ?
                """, ReviewRepo::toReview, submitter, limit);
    }

    private static Review toReview(ResultSet row, int rowNum) throws SQLException {
        return new Review(
                row.getObject("submission_id", UUID.class),
                row.getObject("reviewer_id", UUID.class),
                ReviewVerdict.valueOf(row.getString("verdict").toUpperCase(Locale.ROOT)),
                Optional.ofNullable(row.getString("comment")),
                row.getObject("reviewed_at", OffsetDateTime.class).toInstant());
    }
}

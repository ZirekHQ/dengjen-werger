package werger.adapters.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.ReviewCandidate;
import werger.domain.Submission;

public class SubmissionRepo {
    private final JdbcTemplate jdbc;

    public SubmissionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Submission insert(Submission submission) {
        jdbc.update(
                """
                insert into submissions (id, work_item_id, submitter_id, proposed_translation, submitted_at)
                values (?, ?, ?, ?, ?)
                """,
                submission.id(),
                submission.workItem(),
                submission.submitter(),
                submission.proposedTranslation(),
                OffsetDateTime.ofInstant(submission.submittedAt(), ZoneOffset.UTC));
        return submission;
    }

    /**
     * The earliest submission awaiting review, never the reviewer's own and never one from or for a suspended user, so
     * reviewers are served work in order instead of picking it.
     */
    public Optional<ReviewCandidate> oldestPendingFor(UUID reviewerId) {
        return jdbc.query("""
                        select s.id as submission_id, s.submitter_id, s.proposed_translation, s.submitted_at, w.*
                        from submissions s
                        join work_items w on w.pending_submission_id = s.id and w.status = 'pending_review'
                        join users submitter on submitter.id = s.submitter_id and submitter.status = 'active'
                        where s.submitter_id <> ?
                          and exists (select 1 from users r where r.id = ? and r.status = 'active')
                        order by s.submitted_at, s.id
                        limit 1
                        """, SubmissionRepo::toCandidate, reviewerId, reviewerId).stream()
                .findFirst();
    }

    private static ReviewCandidate toCandidate(ResultSet row, int rowNum) throws SQLException {
        Submission submission = new Submission(
                row.getObject("submission_id", UUID.class),
                row.getObject("id", UUID.class),
                row.getObject("submitter_id", UUID.class),
                row.getString("proposed_translation"),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant());
        return new ReviewCandidate(WorkItemRepo.toWorkItem(row, rowNum), submission);
    }
}

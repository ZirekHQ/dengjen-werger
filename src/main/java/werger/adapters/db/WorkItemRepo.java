package werger.adapters.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;

public class WorkItemRepo {
    private final JdbcTemplate jdbc;

    public WorkItemRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** New items always enter the pool as {@code available}, whatever status the value carries. */
    public WorkItem insert(WorkItem item) {
        jdbc.update(
                """
                insert into work_items
                  (id, source_adapter, external_id, language_code, source_text, target_text_draft, status)
                values (?, ?, ?, ?, ?, ?, 'available')
                """,
                item.id(),
                item.sourceAdapter(),
                item.externalId(),
                item.language(),
                item.sourceText(),
                item.targetTextDraft().orElse(null));
        return item;
    }

    /**
     * Returns true only for the caller whose UPDATE matched a row. The {@code status = 'available'} predicate is what
     * makes concurrent callers race safely in the database instead of both succeeding.
     */
    public boolean tryLease(UUID id, UUID userId, Instant expiresAt) {
        int rows = jdbc.update("""
                update work_items
                set status = 'in_progress', leased_by = ?, lease_expires_at = ?
                where id = ? and status = 'available'
                """, userId, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), id);
        return rows == 1;
    }

    /** Returns true only if the item was leased; an item in any other state is left untouched. */
    public boolean release(UUID id) {
        int rows = jdbc.update("""
                update work_items
                set status = 'available', leased_by = null, lease_expires_at = null
                where id = ? and status = 'in_progress'
                """, id);
        return rows == 1;
    }

    /** Returns leased items whose lease ended before {@code now} to the pool, and reports how many were released. */
    public int releaseExpired(Instant now) {
        return jdbc.update("""
                update work_items
                set status = 'available', leased_by = null, lease_expires_at = null
                where status = 'in_progress' and lease_expires_at < ?
                """, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public Optional<WorkItem> find(UUID id) {
        return jdbc.query("select * from work_items where id = ?", WorkItemRepo::toWorkItem, id).stream()
                .findFirst();
    }

    /** Only the current lease holder can submit; the lease columns are cleared as the item leaves {@code in_progress}. */
    public boolean moveToPendingReview(UUID id, UUID leaseHolder, UUID submissionId) {
        return transition("""
                update work_items
                set status = 'pending_review', pending_submission_id = ?, leased_by = null, lease_expires_at = null
                where id = ? and status = 'in_progress' and leased_by = ?
                """, submissionId, id, leaseHolder);
    }

    /** Only the submission the item is waiting on can decide it, so a stale submission cannot approve a newer one. */
    public boolean markApproved(UUID id, UUID submissionId) {
        return transition("""
                update work_items set status = 'approved'
                where id = ? and status = 'pending_review' and pending_submission_id = ?
                """, id, submissionId);
    }

    public boolean moveToRevisionPending(
            UUID id, UUID submissionId, UUID submitter, Optional<String> comment, Instant expiresAt) {
        return transition("""
                update work_items
                set status = 'revision_pending', pending_submission_id = null,
                    revision_submitter = ?, revision_comment = ?, revision_expires_at = ?
                where id = ? and status = 'pending_review' and pending_submission_id = ?
                """, submitter, comment.orElse(null), utc(expiresAt), id, submissionId);
    }

    /** Lets the rejected submitter lease the item again, but only until the revision deadline passes. */
    public boolean reclaimForRevision(UUID id, UUID userId, Instant now, Instant leaseExpiresAt) {
        return transition("""
                update work_items
                set status = 'in_progress', leased_by = ?, lease_expires_at = ?,
                    revision_submitter = null, revision_comment = null, revision_expires_at = null
                where id = ? and status = 'revision_pending' and revision_submitter = ? and revision_expires_at > ?
                """, userId, utc(leaseExpiresAt), id, userId, utc(now));
    }

    private boolean transition(String sql, Object... args) {
        return jdbc.update(sql, args) == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static WorkItem toWorkItem(ResultSet row, int rowNum) throws SQLException {
        return new WorkItem(
                row.getObject("id", UUID.class),
                row.getString("source_adapter"),
                row.getString("external_id"),
                row.getString("language_code"),
                row.getString("source_text"),
                Optional.ofNullable(row.getString("target_text_draft")),
                toStatus(row));
    }

    private static WorkItemStatus toStatus(ResultSet row) throws SQLException {
        return switch (row.getString("status")) {
            case "available" -> new WorkItemStatus.Available();
            case "in_progress" ->
                new WorkItemStatus.InProgress(
                        row.getObject("leased_by", UUID.class),
                        row.getObject("lease_expires_at", OffsetDateTime.class).toInstant());
            case "pending_review" ->
                new WorkItemStatus.PendingReview(row.getObject("pending_submission_id", UUID.class));
            case "revision_pending" ->
                new WorkItemStatus.RevisionPending(
                        row.getObject("revision_submitter", UUID.class),
                        Optional.ofNullable(row.getString("revision_comment")).orElse(""),
                        row.getObject("revision_expires_at", OffsetDateTime.class)
                                .toInstant());
            case "approved" -> new WorkItemStatus.Approved();
            case "upstream_approval_pending" -> new WorkItemStatus.UpstreamApprovalPending();
            case "synced" -> new WorkItemStatus.Synced();
            case "retired" -> new WorkItemStatus.Retired();
            default -> throw new SQLException("Unknown work item status: " + row.getString("status"));
        };
    }
}

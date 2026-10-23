package werger.adapters.db;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.WorkItem;

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

    public void release(UUID id) {
        jdbc.update("""
                update work_items
                set status = 'available', leased_by = null, lease_expires_at = null
                where id = ?
                """, id);
    }

    /** Returns leased items whose lease ended before {@code now} to the pool, and reports how many were released. */
    public int releaseExpired(Instant now) {
        return jdbc.update("""
                update work_items
                set status = 'available', leased_by = null, lease_expires_at = null
                where status = 'in_progress' and lease_expires_at < ?
                """, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}

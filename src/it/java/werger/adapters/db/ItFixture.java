package werger.adapters.db;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import werger.domain.Submission;

/** Opens the integration database and removes every row a test created, keyed by one adapter name. */
public final class ItFixture implements AutoCloseable {
    private final String adapter;
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    private ItFixture(String adapter, HikariDataSource pool) {
        this.adapter = adapter;
        this.pool = pool;
        this.jdbc = new JdbcTemplate(pool);
    }

    // GitHub passes secrets as empty strings on fork PRs, so a non-empty check is needed, not mere presence.
    private static boolean hasCredentials() {
        return List.of("DB_HOST", "DB_USER", "DB_PASSWORD", "DB_NAME").stream().allMatch(name -> {
            String value = System.getenv(name);
            return value != null && !value.isEmpty();
        });
    }

    public static ItFixture open(String adapter) {
        assumeTrue(hasCredentials(), "set DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME to run against Postgres");
        return new ItFixture(adapter, Db.pooledFromEnv());
    }

    public TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public UUID newUser() {
        return newUser("active");
    }

    public UUID newUser(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into users (id, auth_provider_ref, status) values (?, ?, ?::user_status)",
                id,
                adapter + "-" + id,
                status);
        return id;
    }

    public UUID newItem() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into work_items (id, source_adapter, external_id, language_code, source_text) "
                        + "values (?, ?, ?, 'kmr', 'OK')",
                id,
                adapter,
                "ext-" + id);
        return id;
    }

    /** A new item already waiting for review: a submission by {@code submitter}, linked from the item. */
    public Submission pendingSubmission(UUID submitter, Instant submittedAt) {
        UUID item = newItem();
        UUID submission = UUID.randomUUID();
        jdbc.update(
                "insert into submissions (id, work_item_id, submitter_id, proposed_translation, submitted_at) "
                        + "values (?, ?, ?, 'x', ?)",
                submission,
                item,
                submitter,
                OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC));
        jdbc.update(
                "update work_items set status = 'pending_review', pending_submission_id = ? where id = ?",
                submission,
                item);
        return new Submission(submission, item, submitter, "x", submittedAt);
    }

    public String statusOf(UUID item) {
        return jdbc.queryForObject("select status::text from work_items where id = ?", String.class, item);
    }

    @Override
    public void close() {
        try {
            String ownedItems = "select id from work_items where source_adapter = ?";
            jdbc.update("update work_items set pending_submission_id = null where source_adapter = ?", adapter);
            jdbc.update(
                    "delete from points_ledger where submission_id in (select id from submissions where work_item_id in ("
                            + ownedItems + "))",
                    adapter);
            jdbc.update(
                    "delete from reviews where submission_id in (select id from submissions where work_item_id in ("
                            + ownedItems + "))",
                    adapter);
            jdbc.update("delete from submissions where work_item_id in (" + ownedItems + ")", adapter);
            jdbc.update("delete from work_items where source_adapter = ?", adapter);
            jdbc.update("delete from users where auth_provider_ref like ?", adapter + "-%");
        } finally {
            pool.close();
        }
    }
}

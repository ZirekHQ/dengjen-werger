package werger.adapters.db;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.PointsLedgerEntry;

public class PointsRepo {
    private final JdbcTemplate jdbc;

    public PointsRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if this call inserted the entry, false if one already exists for the same user, submission and reason. */
    public boolean award(PointsLedgerEntry entry) {
        int rows = jdbc.update(
                """
                insert into points_ledger (user_id, submission_id, reason, amount, at)
                values (?, ?, ?::points_reason, ?, ?)
                on conflict (user_id, submission_id, reason) do nothing
                """,
                entry.user(),
                entry.submission(),
                entry.reason().name().toLowerCase(Locale.ROOT),
                entry.amount(),
                OffsetDateTime.ofInstant(entry.at(), ZoneOffset.UTC));
        return rows == 1;
    }
}

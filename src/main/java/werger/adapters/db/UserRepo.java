package werger.adapters.db;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class UserRepo {
    private final JdbcTemplate jdbc;

    public UserRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Strips the personal details and marks the account deleted, keeping the row so contributions stay attributed.
     * Returns false only when no such user exists; purging twice is harmless.
     */
    public boolean purge(UUID id) {
        int rows = jdbc.update("""
                update users
                set email = null, email_opt_in = false, auth_provider_ref = null, status = 'deleted'
                where id = ?
                """, id);
        return rows == 1;
    }
}

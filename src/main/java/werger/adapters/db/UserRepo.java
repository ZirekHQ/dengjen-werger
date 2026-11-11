package werger.adapters.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.Commitment;
import werger.domain.CommitmentTier;
import werger.domain.Role;
import werger.domain.User;
import werger.domain.UserStatus;

/** Users as the sign-in provider knows them, keyed by the provider's stable subject. */
public class UserRepo {
    private final JdbcTemplate jdbc;

    /**
     * Reads and writes users through {@code jdbc}.
     *
     * @param jdbc the pooled connection to the werger database
     */
    public UserRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The user with this provider subject, created as an active contributor on their first sign-in, so there is no
     * separate registration step. Two first sign-ins at once still create one user.
     *
     * @param authProviderRef the {@code sub} claim of the sign-in token
     * @return the existing or newly created user
     */
    public User findOrProvision(String authProviderRef) {
        return findByAuthProviderRef(authProviderRef).orElseGet(() -> {
            jdbc.update(
                    "insert into users (auth_provider_ref) values (?) on conflict (auth_provider_ref) do nothing",
                    authProviderRef);
            return findByAuthProviderRef(authProviderRef)
                    .orElseThrow(() -> new IllegalStateException("user vanished right after provisioning"));
        });
    }

    /**
     * The user with this provider subject, if they have signed in before.
     *
     * @param authProviderRef the {@code sub} claim of the sign-in token
     * @return the user, or empty if none has this subject
     */
    public Optional<User> findByAuthProviderRef(String authProviderRef) {
        return jdbc.query("select * from users where auth_provider_ref = ?", UserRepo::toUser, authProviderRef).stream()
                .findFirst();
    }

    /**
     * Maps one {@code users} row to a {@link User}.
     *
     * @param row the result set positioned on the row
     * @param rowNumber unused
     * @return the user the row holds
     * @throws SQLException if a column cannot be read
     */
    private static User toUser(ResultSet row, int rowNumber) throws SQLException {
        String tier = row.getString("commitment_tier");
        OffsetDateTime startedAt = row.getObject("commitment_started_at", OffsetDateTime.class);
        Optional<Commitment> commitment = tier == null || startedAt == null
                ? Optional.empty()
                : Optional.of(
                        new Commitment(CommitmentTier.valueOf(tier.toUpperCase(Locale.ROOT)), startedAt.toInstant()));
        return new User(
                row.getObject("id", UUID.class),
                row.getString("auth_provider_ref"),
                Role.valueOf(row.getString("role").toUpperCase(Locale.ROOT)),
                UserStatus.valueOf(row.getString("status").toUpperCase(Locale.ROOT)),
                ZoneId.of(row.getString("timezone")),
                commitment,
                Optional.ofNullable(row.getString("email")),
                row.getBoolean("email_opt_in"));
    }
}

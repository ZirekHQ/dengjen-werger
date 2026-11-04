package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserRepoIT {
    private ItFixture db;
    private UserRepo repo;

    @BeforeEach
    void connect() {
        db = ItFixture.open("user-repo-it");
        repo = new UserRepo(db.jdbc());
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private UUID userWithDetails() {
        UUID user = db.newUser();
        db.jdbc().update("update users set email = 'a@example.com', email_opt_in = true where id = ?", user);
        return user;
    }

    private String column(UUID user, String expression) {
        return db.jdbc()
                .queryForObject(
                        "select coalesce((" + expression + ")::text, 'none') from users where id = ?",
                        String.class,
                        user);
    }

    @Test
    void purgeRemovesTheEmailTheAuthLinkAndTheOptIn() {
        UUID user = userWithDetails();

        assertThat(repo.purge(user)).isTrue();

        assertThat(column(user, "email")).isEqualTo("none");
        assertThat(column(user, "auth_provider_ref")).isEqualTo("none");
        assertThat(column(user, "email_opt_in")).isEqualTo("false");
    }

    @Test
    void purgeMarksTheUserDeletedButKeepsTheRowSoTheirContributionsStayAttributed() {
        UUID user = userWithDetails();

        repo.purge(user);

        assertThat(column(user, "status")).isEqualTo("deleted");
        assertThat(column(user, "id")).isEqualTo(user.toString());
    }

    @Test
    void purgeReturnsFalseForAnUnknownUser() {
        assertThat(repo.purge(UUID.randomUUID())).isFalse();
    }

    @Test
    void purgingTwiceIsHarmless() {
        UUID user = userWithDetails();

        repo.purge(user);

        assertThat(repo.purge(user)).isTrue();
        assertThat(column(user, "status")).isEqualTo("deleted");
    }
}

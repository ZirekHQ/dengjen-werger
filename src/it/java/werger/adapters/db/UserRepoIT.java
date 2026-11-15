package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.domain.Role;
import werger.domain.User;
import werger.domain.UserStatus;

class UserRepoIT {
    private static final String ADAPTER = "user-repo-it";

    private ItFixture db;
    private UserRepo repo;

    /** Opens the database, or skips the test when no credentials are set. */
    @BeforeEach
    void connect() {
        db = ItFixture.open(ADAPTER);
        repo = new UserRepo(db.jdbc());
    }

    /** Removes the users this test created. */
    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    /**
     * A subject the fixture's clean-up will remove.
     *
     * @return a new subject owned by this test
     */
    private static String newSubject() {
        return ADAPTER + "-" + UUID.randomUUID();
    }

    /**
     * How many users have this subject.
     *
     * @param subject the provider subject
     * @return the number of rows
     */
    private int countOf(String subject) {
        return db.jdbc()
                .queryForObject("select count(*) from users where auth_provider_ref = ?", Integer.class, subject);
    }

    @Test
    void aFirstSignInCreatesAnActiveContributor() {
        String subject = newSubject();

        User user = repo.findOrProvision(subject);

        assertThat(user.authProviderRef()).isEqualTo(subject);
        assertThat(user.role()).isEqualTo(Role.CONTRIBUTOR);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.timezone().getId()).isEqualTo("UTC");
        assertThat(user.commitment()).isEmpty();
        assertThat(user.email()).isEmpty();
        assertThat(user.emailOptIn()).isFalse();
    }

    @Test
    void aLaterSignInFindsTheSameUser() {
        String subject = newSubject();
        User first = repo.findOrProvision(subject);

        assertThat(repo.findOrProvision(subject)).isEqualTo(first);
        assertThat(countOf(subject)).isEqualTo(1);
    }

    @Test
    void anUnknownSubjectIsNotFound() {
        assertThat(repo.findByAuthProviderRef(newSubject())).isEmpty();
    }

    @Test
    void aStoredCommitmentIsRead() {
        String subject = newSubject();
        repo.findOrProvision(subject);
        db.jdbc()
                .update(
                        "update users set commitment_tier = 'medium', commitment_started_at = now(), "
                                + "timezone = 'Europe/Istanbul' where auth_provider_ref = ?",
                        subject);

        User user = repo.findByAuthProviderRef(subject).orElseThrow();

        assertThat(user.commitment())
                .hasValueSatisfying(c -> assertThat(c.tier().name()).isEqualTo("MEDIUM"));
        assertThat(user.timezone().getId()).isEqualTo("Europe/Istanbul");
    }

    @Test
    void simultaneousFirstSignInsCreateOneUser() throws Exception {
        String subject = newSubject();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<User>> futures = IntStream.range(0, 8)
                    .<Callable<User>>mapToObj(i -> () -> {
                        start.await();
                        return repo.findOrProvision(subject);
                    })
                    .map(executor::submit)
                    .toList();
            start.countDown();
            List<UUID> ids = futures.stream()
                    .map(UserRepoIT::get)
                    .map(User::id)
                    .distinct()
                    .toList();

            assertThat(ids).hasSize(1);
            assertThat(countOf(subject)).isEqualTo(1);
        }
    }

    /**
     * The value of a finished future.
     *
     * @param future a future that completed
     * @return its value
     */
    private static User get(Future<User> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

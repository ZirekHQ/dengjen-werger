package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import werger.domain.PointsLedgerEntry;
import werger.domain.PointsReason;

class PointsRepoIT {
    private ItFixture db;
    private PointsRepo repo;
    private UUID user;
    private UUID submission;

    @BeforeEach
    void connect() {
        db = ItFixture.open("points-repo-it");
        repo = new PointsRepo(db.jdbc());
        user = db.newUser();
        submission = db.pendingSubmission(db.newUser(), Instant.now()).id();
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private PointsLedgerEntry entry(PointsReason reason, int amount) {
        return new PointsLedgerEntry(user, submission, reason, amount, Instant.now());
    }

    private int total() {
        return db.jdbc()
                .queryForObject(
                        "select coalesce(sum(amount), 0) from points_ledger where user_id = ?", Integer.class, user);
    }

    @Test
    void awardStoresTheEntry() {
        assertThat(repo.award(entry(PointsReason.SUBMISSION_APPROVED, 10))).isTrue();
        assertThat(total()).isEqualTo(10);
    }

    @Test
    void theSamePayoutTwiceAwardsNothingTheSecondTime() {
        repo.award(entry(PointsReason.REVIEW_COMPLETED, 5));

        assertThat(repo.award(entry(PointsReason.REVIEW_COMPLETED, 5))).isFalse();
        assertThat(total()).isEqualTo(5);
    }

    @Test
    void aRepeatCannotRaiseTheAmount() {
        repo.award(entry(PointsReason.REVIEW_COMPLETED, 5));

        repo.award(entry(PointsReason.REVIEW_COMPLETED, 500));

        assertThat(total()).isEqualTo(5);
    }

    @Test
    void aDifferentReasonForTheSameSubmissionStillPays() {
        repo.award(entry(PointsReason.SUBMISSION_APPROVED, 10));

        assertThat(repo.award(entry(PointsReason.DRAFT_CONFIRMED, 3))).isTrue();
        assertThat(total()).isEqualTo(13);
    }

    @Test
    void simultaneousAwardsOfOnePayoutPayExactlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = IntStream.range(0, 8)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        start.await();
                        return repo.award(entry(PointsReason.REVIEW_COMPLETED, 5));
                    })
                    .map(executor::submit)
                    .toList();
            start.countDown();
            long paid = futures.stream().filter(PointsRepoIT::get).count();

            assertThat(paid).isEqualTo(1);
            assertThat(total()).isEqualTo(5);
        }
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

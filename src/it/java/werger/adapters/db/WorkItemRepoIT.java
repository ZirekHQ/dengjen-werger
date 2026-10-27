package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;

class WorkItemRepoIT {
    private static final String ADAPTER = "work-item-repo-it";

    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private WorkItemRepo repo;

    // Without a configured database the test is skipped rather than failed.
    private static boolean hasCredentials() {
        return List.of("DB_HOST", "DB_USER", "DB_PASSWORD", "DB_NAME").stream().allMatch(name -> {
            String value = System.getenv(name);
            return value != null && !value.isEmpty();
        });
    }

    @BeforeEach
    void connect() {
        assumeTrue(hasCredentials(), "set DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME to run against Postgres");
        pool = Db.pooledFromEnv();
        jdbc = new JdbcTemplate(pool);
        repo = new WorkItemRepo(jdbc);
    }

    @AfterEach
    void cleanUp() {
        if (jdbc != null) {
            jdbc.update("update work_items set pending_submission_id = null where source_adapter = ?", ADAPTER);
            jdbc.update(
                    "delete from submissions where work_item_id in (select id from work_items where source_adapter = ?)",
                    ADAPTER);
            jdbc.update("delete from work_items where source_adapter = ?", ADAPTER);
            jdbc.update("delete from users where auth_provider_ref like ?", ADAPTER + "%");
            pool.close();
        }
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, auth_provider_ref) values (?, ?)", id, ADAPTER + "-" + id);
        return id;
    }

    private WorkItem newItem() {
        return repo.insert(new WorkItem(
                UUID.randomUUID(),
                ADAPTER,
                "ext-" + UUID.randomUUID(),
                "kmr",
                "OK",
                Optional.empty(),
                new WorkItemStatus.Available()));
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status::text from work_items where id = ?", String.class, id);
    }

    @Test
    void insertStoresTheItemAsAvailable() {
        WorkItem item = newItem();

        assertThat(statusOf(item.id())).isEqualTo("available");
    }

    @Test
    void insertReturnsTheStatusItStored() {
        WorkItem stored = repo.insert(new WorkItem(
                UUID.randomUUID(),
                ADAPTER,
                "ext-" + UUID.randomUUID(),
                "kmr",
                "OK",
                Optional.empty(),
                new WorkItemStatus.Approved()));

        assertThat(stored.status()).isEqualTo(new WorkItemStatus.Available());
    }

    @Test
    void insertStoresTheDraftText() {
        WorkItem item = repo.insert(new WorkItem(
                UUID.randomUUID(),
                ADAPTER,
                "ext-" + UUID.randomUUID(),
                "kmr",
                "OK",
                Optional.of("Baş e"),
                new WorkItemStatus.Available()));

        assertThat(jdbc.queryForObject(
                        "select target_text_draft from work_items where id = ?", String.class, item.id()))
                .isEqualTo("Baş e");
    }

    @Test
    void tryLeaseMarksTheItemInProgressForTheLeaser() {
        WorkItem item = newItem();
        UUID user = newUser();

        boolean won = repo.tryLease(item.id(), user, Instant.now().plusSeconds(1800));

        assertThat(won).isTrue();
        assertThat(statusOf(item.id())).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject("select leased_by from work_items where id = ?", UUID.class, item.id()))
                .isEqualTo(user);
    }

    @Test
    void anItemAlreadyLeasedCannotBeLeasedAgain() {
        WorkItem item = newItem();
        Instant expiry = Instant.now().plusSeconds(1800);
        repo.tryLease(item.id(), newUser(), expiry);

        assertThat(repo.tryLease(item.id(), newUser(), expiry)).isFalse();
    }

    @Test
    void simultaneousLeaseAttemptsOnOneItemHaveExactlyOneWinner() throws Exception {
        WorkItem item = newItem();
        List<UUID> contenders = IntStream.range(0, 8).mapToObj(i -> newUser()).toList();
        CountDownLatch start = new CountDownLatch(1);
        Instant expiry = Instant.now().plusSeconds(1800);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> attempts = contenders.stream()
                    .<Callable<Boolean>>map(user -> () -> {
                        start.await();
                        return repo.tryLease(item.id(), user, expiry);
                    })
                    .toList();
            List<Future<Boolean>> futures =
                    attempts.stream().map(executor::submit).toList();
            start.countDown();
            long winners = futures.stream().filter(WorkItemRepoIT::get).count();

            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void releaseMakesTheItemAvailableAndClearsTheLease() {
        WorkItem item = newItem();
        UUID holder = newUser();
        repo.tryLease(item.id(), holder, Instant.now().plusSeconds(1800));

        boolean released = repo.release(item.id(), holder);

        assertThat(released).isTrue();
        assertThat(statusOf(item.id())).isEqualTo("available");
        assertThat(jdbc.queryForObject("select leased_by from work_items where id = ?", UUID.class, item.id()))
                .isNull();
        assertThat(repo.tryLease(item.id(), newUser(), Instant.now().plusSeconds(1800)))
                .isTrue();
    }

    @Test
    void releaseLeavesAnItemThatIsNotInProgressUntouched() {
        WorkItem item = newItem();
        jdbc.update("update work_items set status = 'pending_review' where id = ?", item.id());

        boolean released = repo.release(item.id(), newUser());

        assertThat(released).isFalse();
        assertThat(statusOf(item.id())).isEqualTo("pending_review");
    }

    @Test
    void releaseLeavesAnItemLeasedToSomeoneElse() {
        WorkItem item = newItem();
        UUID holder = newUser();
        repo.tryLease(item.id(), holder, Instant.now().plusSeconds(1800));

        boolean released = repo.release(item.id(), newUser());

        assertThat(released).isFalse();
        assertThat(statusOf(item.id())).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject("select leased_by from work_items where id = ?", UUID.class, item.id()))
                .isEqualTo(holder);
    }

    @Test
    void releaseExpiredFreesOnlyItemsWhoseLeaseHasEnded() {
        WorkItem expired = newItem();
        WorkItem active = newItem();
        Instant now = Instant.now();
        repo.tryLease(expired.id(), newUser(), now.minusSeconds(60));
        repo.tryLease(active.id(), newUser(), now.plusSeconds(1800));

        repo.releaseExpired(now);

        assertThat(statusOf(expired.id())).isEqualTo("available");
        assertThat(statusOf(active.id())).isEqualTo("in_progress");
        assertThat(repo.tryLease(expired.id(), newUser(), now.plusSeconds(1800)))
                .isTrue();
    }

    private void awaitReview(UUID item, UUID submission) {
        jdbc.update(
                "update work_items set status = 'pending_review', pending_submission_id = ? where id = ?",
                submission,
                item);
    }

    @Test
    void findMapsAnInProgressItemWithItsLease() {
        WorkItem item = newItem();
        UUID user = newUser();
        Instant expiry = Instant.parse("2026-09-01T12:30:00Z");
        repo.tryLease(item.id(), user, expiry);

        assertThat(repo.find(item.id()))
                .hasValueSatisfying(
                        found -> assertThat(found.status()).isEqualTo(new WorkItemStatus.InProgress(user, expiry)));
    }

    @Test
    void findReturnsEmptyForAnUnknownId() {
        assertThat(repo.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void moveToPendingReviewRequiresTheLeaseHolder() {
        WorkItem item = newItem();
        UUID holder = newUser();
        UUID submission = newSubmission(item.id(), holder);
        repo.tryLease(item.id(), holder, Instant.now().plusSeconds(1800));

        assertThat(repo.moveToPendingReview(item.id(), newUser(), submission)).isFalse();
        assertThat(statusOf(item.id())).isEqualTo("in_progress");
        assertThat(repo.moveToPendingReview(item.id(), holder, submission)).isTrue();
        assertThat(repo.find(item.id()).orElseThrow().status()).isEqualTo(new WorkItemStatus.PendingReview(submission));
    }

    @Test
    void markApprovedOnlyFromPendingReviewForTheCurrentSubmission() {
        WorkItem item = newItem();
        UUID submission = newSubmission(item.id(), newUser());

        assertThat(repo.markApproved(item.id(), submission)).isFalse();
        awaitReview(item.id(), submission);
        assertThat(repo.markApproved(item.id(), UUID.randomUUID())).isFalse();
        assertThat(repo.markApproved(item.id(), submission)).isTrue();
        assertThat(statusOf(item.id())).isEqualTo("approved");
    }

    @Test
    void moveToRevisionPendingStoresSubmitterCommentAndDeadline() {
        WorkItem item = newItem();
        UUID submitter = newUser();
        UUID submission = newSubmission(item.id(), submitter);
        Instant deadline = Instant.parse("2026-09-03T12:00:00Z");
        awaitReview(item.id(), submission);

        assertThat(repo.moveToRevisionPending(
                        item.id(), UUID.randomUUID(), submitter, Optional.of("wrong register"), deadline))
                .isFalse();
        assertThat(repo.moveToRevisionPending(
                        item.id(), submission, submitter, Optional.of("wrong register"), deadline))
                .isTrue();
        assertThat(repo.find(item.id()).orElseThrow().status())
                .isEqualTo(new WorkItemStatus.RevisionPending(submitter, "wrong register", deadline));
    }

    @Test
    void moveToRevisionPendingAcceptsNoComment() {
        WorkItem item = newItem();
        UUID submission = newSubmission(item.id(), newUser());
        awaitReview(item.id(), submission);

        repo.moveToRevisionPending(
                item.id(),
                submission,
                newUser(),
                Optional.empty(),
                Instant.now().plusSeconds(60));

        assertThat(repo.find(item.id()).orElseThrow().status())
                .isInstanceOfSatisfying(
                        WorkItemStatus.RevisionPending.class,
                        revision -> assertThat(revision.reviewerComment()).isEmpty());
    }

    @Test
    void reclaimForRevisionOnlyBySubmitterBeforeTheDeadline() {
        WorkItem item = newItem();
        UUID submitter = newUser();
        Instant now = Instant.now();
        UUID submission = newSubmission(item.id(), submitter);
        awaitReview(item.id(), submission);
        repo.moveToRevisionPending(item.id(), submission, submitter, Optional.empty(), now.plusSeconds(3600));

        assertThat(repo.reclaimForRevision(item.id(), newUser(), now, now.plusSeconds(1800)))
                .isFalse();
        assertThat(repo.reclaimForRevision(item.id(), submitter, now.plusSeconds(7200), now.plusSeconds(9000)))
                .isFalse();
        assertThat(repo.reclaimForRevision(item.id(), submitter, now, now.plusSeconds(1800)))
                .isTrue();
        assertThat(repo.find(item.id()).orElseThrow().status()).isInstanceOf(WorkItemStatus.InProgress.class);
    }

    private UUID newSubmission(UUID workItem, UUID submitter) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into submissions (id, work_item_id, submitter_id, proposed_translation) values (?, ?, ?, 'x')",
                id,
                workItem,
                submitter);
        return id;
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

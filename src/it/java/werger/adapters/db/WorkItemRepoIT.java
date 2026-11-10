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
            try {
                jdbc.update("update work_items set pending_submission_id = null where source_adapter = ?", ADAPTER);
                jdbc.update(
                        "delete from submissions where work_item_id in (select id from work_items where source_adapter = ?)",
                        ADAPTER);
                jdbc.update("delete from work_items where source_adapter = ?", ADAPTER);
                jdbc.update("delete from users where auth_provider_ref like ?", ADAPTER + "%");
            } finally {
                pool.close();
            }
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

    private WorkItem revisionPendingUntil(UUID submitter, Instant deadline) {
        WorkItem item = newItem();
        UUID submission = newSubmission(item.id(), submitter);
        awaitReview(item.id(), submission);
        repo.moveToRevisionPending(item.id(), submission, submitter, Optional.of("fix"), deadline);
        return item;
    }

    // Fixed past instants keep the sweep from touching revisions that other data in the dev database still has open.
    private static final Instant SWEEP_NOW = Instant.parse("2000-01-02T00:00:00Z");

    @Test
    void reopenExpiredRevisionsFreesOnlyItemsPastTheirDeadline() {
        WorkItem lapsed = revisionPendingUntil(newUser(), SWEEP_NOW.minusSeconds(60));
        WorkItem running = revisionPendingUntil(newUser(), SWEEP_NOW.plusSeconds(3600));

        repo.reopenExpiredRevisions(SWEEP_NOW);

        assertThat(statusOf(lapsed.id())).isEqualTo("available");
        assertThat(statusOf(running.id())).isEqualTo("revision_pending");
    }

    @Test
    void anItemWhoseDeadlineIsExactlyNowIsReopenedRatherThanStranded() {
        UUID submitter = newUser();
        WorkItem item = revisionPendingUntil(submitter, SWEEP_NOW);

        // reclaimForRevision needs deadline > now, so at the deadline only the sweep can move the item.
        assertThat(repo.reclaimForRevision(item.id(), submitter, SWEEP_NOW, SWEEP_NOW.plusSeconds(1800)))
                .isFalse();
        repo.reopenExpiredRevisions(SWEEP_NOW);

        assertThat(statusOf(item.id())).isEqualTo("available");
    }

    @Test
    void aReopenedItemKeepsNoRevisionStateAndCanBeLeasedByAnyone() {
        WorkItem lapsed = revisionPendingUntil(newUser(), SWEEP_NOW.minusSeconds(60));

        repo.reopenExpiredRevisions(SWEEP_NOW);

        assertThat(jdbc.queryForObject(
                        "select count(*) from work_items where id = ? and revision_submitter is null"
                                + " and revision_comment is null and revision_expires_at is null",
                        Integer.class,
                        lapsed.id()))
                .isEqualTo(1);
        assertThat(repo.tryLease(lapsed.id(), newUser(), SWEEP_NOW.plusSeconds(1800)))
                .isTrue();
    }

    @Test
    void reopenExpiredRevisionsLeavesItemsInOtherStatesUntouched() {
        WorkItem leased = newItem();
        repo.tryLease(leased.id(), newUser(), SWEEP_NOW.plusSeconds(1800));

        repo.reopenExpiredRevisions(SWEEP_NOW);

        assertThat(statusOf(leased.id())).isEqualTo("in_progress");
    }

    @Test
    void aSweepRacingTheSubmittersReclaimLeavesTheItemInExactlyOneState() throws Exception {
        UUID submitter = newUser();
        Instant deadline = SWEEP_NOW.minusSeconds(60);
        WorkItem item = revisionPendingUntil(submitter, deadline);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // The two callers disagree about whether the deadline has passed, so only the status gate can decide.
            Future<Boolean> reclaim = executor.submit(() -> {
                start.await();
                return repo.reclaimForRevision(
                        item.id(), submitter, deadline.minusSeconds(1), deadline.plusSeconds(1800));
            });
            Future<Integer> sweep = executor.submit(() -> {
                start.await();
                return repo.reopenExpiredRevisions(SWEEP_NOW);
            });
            start.countDown();
            boolean reclaimed = get(reclaim);
            sweep.get();

            assertThat(statusOf(item.id())).isEqualTo(reclaimed ? "in_progress" : "available");
        }
    }

    private String leasedByOf(UUID id) {
        return jdbc.queryForObject(
                "select coalesce(leased_by::text, 'none') from work_items where id = ?", String.class, id);
    }

    @Test
    void releaseUnfinishedWorkOfFreesAnItemTheUserHasLeased() {
        UUID user = newUser();
        WorkItem leased = newItem();
        repo.tryLease(leased.id(), user, Instant.now().plusSeconds(1800));

        int released = repo.releaseUnfinishedWorkOf(user);

        assertThat(released).isEqualTo(1);
        assertThat(statusOf(leased.id())).isEqualTo("available");
        assertThat(leasedByOf(leased.id())).isEqualTo("none");
    }

    @Test
    void releaseUnfinishedWorkOfFreesAnItemWaitingOnTheUsersSubmission() {
        UUID user = newUser();
        WorkItem item = newItem();
        awaitReview(item.id(), newSubmission(item.id(), user));

        repo.releaseUnfinishedWorkOf(user);

        assertThat(repo.find(item.id()).orElseThrow().status()).isEqualTo(new WorkItemStatus.Available());
        assertThat(jdbc.queryForObject(
                        "select pending_submission_id is null from work_items where id = ?", Boolean.class, item.id()))
                .isTrue();
    }

    @Test
    void releaseUnfinishedWorkOfFreesAnItemWaitingOnTheUsersRevision() {
        UUID user = newUser();
        WorkItem item = revisionPendingUntil(user, Instant.now().plusSeconds(3600));

        repo.releaseUnfinishedWorkOf(user);

        assertThat(statusOf(item.id())).isEqualTo("available");
        assertThat(jdbc.queryForObject(
                        "select revision_submitter is null and revision_expires_at is null"
                                + " from work_items where id = ?",
                        Boolean.class,
                        item.id()))
                .isTrue();
    }

    @Test
    void releaseUnfinishedWorkOfKeepsApprovedContributionsAndOtherUsersWork() {
        UUID user = newUser();
        UUID other = newUser();
        WorkItem approved = newItem();
        UUID approvedSubmission = newSubmission(approved.id(), user);
        awaitReview(approved.id(), approvedSubmission);
        repo.markApproved(approved.id(), approvedSubmission);
        WorkItem othersPending = newItem();
        awaitReview(othersPending.id(), newSubmission(othersPending.id(), other));
        WorkItem othersLeased = newItem();
        repo.tryLease(othersLeased.id(), other, Instant.now().plusSeconds(1800));

        repo.releaseUnfinishedWorkOf(user);

        assertThat(statusOf(approved.id())).isEqualTo("approved");
        assertThat(statusOf(othersPending.id())).isEqualTo("pending_review");
        assertThat(statusOf(othersLeased.id())).isEqualTo("in_progress");
    }

    @Test
    void aReleaseRacingTheReviewVerdictLeavesTheItemInExactlyOneState() throws Exception {
        UUID author = newUser();
        WorkItem item = newItem();
        UUID submission = newSubmission(item.id(), author);
        awaitReview(item.id(), submission);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> approve = executor.submit(() -> {
                start.await();
                return repo.markApproved(item.id(), submission);
            });
            Future<Integer> release = executor.submit(() -> {
                start.await();
                return repo.releaseUnfinishedWorkOf(author);
            });
            start.countDown();
            boolean approved = get(approve);
            release.get();

            assertThat(statusOf(item.id())).isEqualTo(approved ? "approved" : "available");
        }
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

    private UUID itemWithStatus(String status) {
        UUID id = newItem().id();
        jdbc.update("update work_items set status = ?::work_item_status where id = ?", status, id);
        return id;
    }

    @Test
    void markAwaitingCountersignOnlyFromPendingReviewForTheCurrentSubmission() {
        WorkItem item = newItem();
        UUID submission = newSubmission(item.id(), newUser());

        assertThat(repo.markAwaitingCountersign(item.id(), submission)).isFalse();
        awaitReview(item.id(), submission);
        assertThat(repo.markAwaitingCountersign(item.id(), UUID.randomUUID())).isFalse();
        assertThat(repo.markAwaitingCountersign(item.id(), submission)).isTrue();
        assertThat(statusOf(item.id())).isEqualTo("awaiting_countersign");
    }

    @Test
    void findMapsAnItemAwaitingCountersign() {
        UUID waiting = itemWithStatus("awaiting_countersign");

        assertThat(repo.find(waiting).orElseThrow().status()).isEqualTo(new WorkItemStatus.AwaitingCountersign());
    }

    @Test
    void anItemAwaitingCountersignCannotBePushedUntilItIsApprovedAgain() {
        UUID waiting = itemWithStatus("awaiting_countersign");

        assertThat(repo.markSynced(waiting)).isFalse();
        assertThat(repo.markUpstreamApprovalPending(waiting)).isFalse();
        assertThat(statusOf(waiting)).isEqualTo("awaiting_countersign");
    }

    @Test
    void markSyncedOnlyMovesAnApprovedItem() {
        UUID approved = itemWithStatus("approved");
        UUID available = newItem().id();

        assertThat(repo.markSynced(approved)).isTrue();
        assertThat(repo.markSynced(available)).isFalse();
        assertThat(statusOf(approved)).isEqualTo("synced");
        assertThat(statusOf(available)).isEqualTo("available");
    }

    @Test
    void markSyncedAlsoCompletesAnItemQueuedForUpstreamApproval() {
        UUID queued = itemWithStatus("upstream_approval_pending");
        UUID synced = itemWithStatus("synced");

        assertThat(repo.markSynced(queued)).isTrue();
        assertThat(repo.markSynced(synced)).isFalse();
        assertThat(statusOf(queued)).isEqualTo("synced");
    }

    @Test
    void markUpstreamApprovalPendingOnlyMovesAnApprovedItem() {
        UUID approved = itemWithStatus("approved");
        UUID synced = itemWithStatus("synced");

        assertThat(repo.markUpstreamApprovalPending(approved)).isTrue();
        assertThat(repo.markUpstreamApprovalPending(synced)).isFalse();
        assertThat(statusOf(approved)).isEqualTo("upstream_approval_pending");
        assertThat(statusOf(synced)).isEqualTo("synced");
    }
}

package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.adapters.db.ItFixture;
import werger.adapters.db.PointsRepo;
import werger.adapters.db.UserRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.PointsLedgerEntry;
import werger.domain.PointsReason;
import werger.domain.Submission;
import werger.ports.Result;

class AccountDeletionServiceIT {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private ItFixture db;
    private WorkItemRepo workItems;
    private AccountDeletionService service;

    @BeforeEach
    void connect() {
        db = ItFixture.open("account-deletion-it");
        workItems = new WorkItemRepo(db.jdbc());
        service = new AccountDeletionService(new UserRepo(db.jdbc()), workItems, db.transactions());
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private Submission syncedContribution(UUID user) {
        Submission submission = db.pendingSubmission(user, NOW);
        db.jdbc().update("update work_items set status = 'synced' where id = ?", submission.workItem());
        new PointsRepo(db.jdbc())
                .award(new PointsLedgerEntry(user, submission.id(), PointsReason.SUBMISSION_APPROVED, 10, NOW));
        return submission;
    }

    private long count(String sql, Object... args) {
        return db.jdbc().queryForObject(sql, Long.class, args);
    }

    @Test
    void deletingAnAccountPurgesTheirDetailsButKeepsTheirSyncedContributionAttributedWithItsPoints() {
        UUID user = db.newUser();
        db.jdbc().update("update users set email = 'a@example.com' where id = ?", user);
        Submission kept = syncedContribution(user);

        Result<DeletionError, Void> result = service.delete(user);

        assertThat(result).isEqualTo(Result.ok(null));
        assertThat(count("select count(*) from users where id = ? and email is null and status = 'deleted'", user))
                .isEqualTo(1);
        assertThat(db.statusOf(kept.workItem())).isEqualTo("synced");
        assertThat(count("select count(*) from submissions where id = ? and submitter_id = ?", kept.id(), user))
                .isEqualTo(1);
        assertThat(count("select count(*) from points_ledger where user_id = ? and submission_id = ?", user, kept.id()))
                .isEqualTo(1);
    }

    @Test
    void deletingAnAccountReleasesTheirLeasedAndPendingWorkBackToTheQueue() {
        UUID user = db.newUser();
        UUID leased = db.newItem();
        workItems.tryLease(leased, user, NOW.plusSeconds(1800));
        Submission pending = db.pendingSubmission(user, NOW);

        service.delete(user);

        assertThat(db.statusOf(leased)).isEqualTo("available");
        assertThat(db.statusOf(pending.workItem())).isEqualTo("available");
    }

    @Test
    void deletingAnUnknownAccountReportsItAndChangesNothing() {
        UUID bystander = db.newUser();
        UUID leased = db.newItem();
        workItems.tryLease(leased, bystander, NOW.plusSeconds(1800));

        Result<DeletionError, Void> result = service.delete(UUID.randomUUID());

        assertThat(result).isEqualTo(Result.err(new DeletionError.UnknownUser()));
        assertThat(db.statusOf(leased)).isEqualTo("in_progress");
    }
}

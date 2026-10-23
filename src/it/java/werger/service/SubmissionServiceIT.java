package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.adapters.db.ItFixture;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.WorkItem;
import werger.ports.Result;

class SubmissionServiceIT {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private ItFixture db;
    private WorkItemRepo workItems;
    private SubmissionService service;

    @BeforeEach
    void connect() {
        db = ItFixture.open("submission-service-it");
        workItems = new WorkItemRepo(db.jdbc());
        service = new SubmissionService(
                workItems, new SubmissionRepo(db.jdbc()), db.transactions(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private WorkItem leasedBy(UUID user) {
        UUID id = db.newItem();
        workItems.tryLease(id, user, NOW.plusSeconds(1800));
        return workItems.find(id).orElseThrow();
    }

    private int submissionCount(UUID item) {
        return db.jdbc().queryForObject("select count(*) from submissions where work_item_id = ?", Integer.class, item);
    }

    @Test
    void submitMovesTheItemToPendingReview() {
        UUID holder = db.newUser();
        WorkItem item = leasedBy(holder);

        var result = service.submit(item, holder, "Silav");

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(db.statusOf(item.id())).isEqualTo("pending_review");
        assertThat(submissionCount(item.id())).isEqualTo(1);
    }

    @Test
    void submitByANonHolderIsRefusedAndLeavesNoSubmission() {
        WorkItem item = leasedBy(db.newUser());

        var result = service.submit(item, db.newUser(), "Silav");

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class,
                        err -> assertThat(err.error()).isInstanceOf(SubmitError.NotLeaseHolder.class));
        assertThat(submissionCount(item.id())).isZero();
        assertThat(db.statusOf(item.id())).isEqualTo("in_progress");
    }
}

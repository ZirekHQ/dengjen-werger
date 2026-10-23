package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.domain.ReviewCandidate;
import werger.domain.Submission;

class SubmissionRepoIT {
    private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");

    private ItFixture db;
    private SubmissionRepo repo;

    @BeforeEach
    void connect() {
        db = ItFixture.open("submission-repo-it");
        repo = new SubmissionRepo(db.jdbc());
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void insertStoresTheSubmission() {
        UUID item = db.newItem();
        UUID submitter = db.newUser();
        Submission submission = new Submission(UUID.randomUUID(), item, submitter, "Silav", T0);

        repo.insert(submission);

        assertThat(db.jdbc()
                        .queryForObject(
                                "select proposed_translation from submissions where id = ?",
                                String.class,
                                submission.id()))
                .isEqualTo("Silav");
    }

    @Test
    void oldestPendingForReturnsTheEarliestSubmission() {
        UUID submitter = db.newUser();
        UUID later = db.pendingSubmission(submitter, T0.plusSeconds(60)).id();
        UUID earlier = db.pendingSubmission(submitter, T0).id();

        assertThat(repo.oldestPendingFor(db.newUser()))
                .map(candidate -> candidate.submission().id())
                .contains(earlier);
        assertThat(later).isNotEqualTo(earlier);
    }

    @Test
    void oldestPendingForCarriesTheItemInPendingReview() {
        UUID submission = db.pendingSubmission(db.newUser(), T0).id();

        ReviewCandidate candidate = repo.oldestPendingFor(db.newUser()).orElseThrow();

        assertThat(candidate.submission().id()).isEqualTo(submission);
        assertThat(candidate.item().id()).isEqualTo(candidate.submission().workItem());
    }

    @Test
    void oldestPendingForSkipsTheReviewersOwnSubmission() {
        UUID reviewer = db.newUser();
        UUID other = db.newUser();
        db.pendingSubmission(reviewer, T0);
        UUID theirs = db.pendingSubmission(other, T0.plusSeconds(60)).id();

        assertThat(repo.oldestPendingFor(reviewer))
                .map(candidate -> candidate.submission().id())
                .contains(theirs);
    }

    @Test
    void oldestPendingForIsEmptyWhenOnlyTheReviewersOwnWorkIsPending() {
        UUID reviewer = db.newUser();
        db.pendingSubmission(reviewer, T0);

        assertThat(repo.oldestPendingFor(reviewer)).isEmpty();
    }

    @Test
    void oldestPendingForExcludesSuspendedSubmittersAndSuspendedReviewers() {
        UUID suspendedSubmitter = db.newUser("suspended");
        UUID active = db.pendingSubmission(db.newUser(), T0.plusSeconds(60)).id();
        db.pendingSubmission(suspendedSubmitter, T0);

        assertThat(repo.oldestPendingFor(db.newUser()))
                .map(candidate -> candidate.submission().id())
                .contains(active);
        assertThat(repo.oldestPendingFor(db.newUser("suspended"))).isEmpty();
    }
}

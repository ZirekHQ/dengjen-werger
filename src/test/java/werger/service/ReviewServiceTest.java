package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.PointsRepo;
import werger.adapters.db.ReviewRepo;
import werger.adapters.db.SubmissionRepo;
import werger.adapters.db.WorkItemRepo;
import werger.domain.Language;
import werger.domain.PointsLedgerEntry;
import werger.domain.PointsReason;
import werger.domain.Review;
import werger.domain.ReviewVerdict;
import werger.domain.Role;
import werger.domain.Submission;
import werger.domain.TmSegment;
import werger.domain.WorkItem;
import werger.ports.Result;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

class ReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final Submission SUBMISSION =
            new Submission(UUID.randomUUID(), UUID.randomUUID(), SUBMITTER, "x", NOW);

    /** Hand-rolled stubs: the transitions and the race are proven against real Postgres in ReviewServiceIT. */
    private static final class StubWorkItems extends WorkItemRepo {
        final List<String> calls = new ArrayList<>();
        boolean transitionWins = true;
        Instant revisionDeadline;
        Optional<String> revisionComment = Optional.empty();

        StubWorkItems() {
            super(new JdbcTemplate());
        }

        @Override
        public boolean markApproved(UUID id, UUID submissionId) {
            calls.add("approved");
            return transitionWins;
        }

        @Override
        public boolean markApprovedAwaitingCountersign(UUID id, UUID submissionId) {
            calls.add("awaiting_countersign");
            return transitionWins;
        }

        @Override
        public boolean moveToRevisionPending(
                UUID id, UUID submissionId, UUID submitter, Optional<String> comment, Instant expiresAt) {
            calls.add("revision:" + submitter);
            revisionDeadline = expiresAt;
            revisionComment = comment;
            return transitionWins;
        }
    }

    private static final class StubReviews extends ReviewRepo {
        final List<Review> inserted = new ArrayList<>();
        List<Review> outcomes = List.of();

        StubReviews() {
            super(new JdbcTemplate());
        }

        @Override
        public List<Review> decidedOutcomesOf(UUID submitter, int limit) {
            return outcomes;
        }

        @Override
        public void insert(Review review) {
            inserted.add(review);
        }
    }

    private static final class StubPoints extends PointsRepo {
        final List<PointsLedgerEntry> awarded = new ArrayList<>();

        StubPoints() {
            super(new JdbcTemplate());
        }

        @Override
        public boolean award(PointsLedgerEntry entry) {
            awarded.add(entry);
            return true;
        }
    }

    private static final class StubApprovals extends ApprovalService {
        final List<String> calls = new ArrayList<>();

        StubApprovals() {
            super(new NoSource(), new WorkItemRepo(new JdbcTemplate()), Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Override
        public ApprovalOutcome onApproved(Submission submission) {
            calls.add(submission.id().toString());
            return new ApprovalOutcome.Synced();
        }
    }

    private static final class NoSource implements TranslationSource {
        @Override
        public String adapterName() {
            return "stub";
        }

        @Override
        public List<WorkItem> fetchWorkItems(Language language) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TmSegment> fetchMemory(Language language) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<SourceError, Void> submit(WorkItem item, String translation) {
            throw new UnsupportedOperationException();
        }
    }

    private final StubWorkItems workItems = new StubWorkItems();
    private final StubReviews reviews = new StubReviews();
    private final StubPoints points = new StubPoints();
    private final StubApprovals approvals = new StubApprovals();
    private final ReviewService service = new ReviewService(
            workItems,
            new SubmissionRepo(new JdbcTemplate()),
            reviews,
            points,
            approvals,
            NoTransactions.template(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reviewerCannotReviewTheirOwnSubmissionAndNothingIsWritten() {
        var result = service.review(SUBMISSION, SUBMITTER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(ReviewError.OwnSubmission.class));
        assertThat(workItems.calls).isEmpty();
        assertThat(reviews.inserted).isEmpty();
    }

    @Test
    void aTrustedApprovalMarksTheItemApprovedAndRecordsTheReview() {
        var result = service.review(SUBMISSION, REVIEWER, Role.MAINTAINER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(workItems.calls).containsExactly("approved");
        assertThat(reviews.inserted).singleElement().satisfies(review -> {
            assertThat(review.verdict()).isEqualTo(ReviewVerdict.APPROVED);
            assertThat(review.reviewer()).isEqualTo(REVIEWER);
            assertThat(review.reviewedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void rejectionGivesTheSubmitterAFortyEightHourWindowFromTheClock() {
        service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.REJECTED, Optional.of("wrong register"));

        assertThat(workItems.calls).containsExactly("revision:" + SUBMITTER);
        assertThat(workItems.revisionDeadline).isEqualTo(NOW.plusSeconds(48 * 3_600));
        assertThat(workItems.revisionComment).contains("wrong register");
    }

    @Test
    void aLostRaceReportsAlreadyDecidedAndRecordsNoReview() {
        workItems.transitionWins = false;

        var result = service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class,
                        err -> assertThat(err.error()).isInstanceOf(ReviewError.AlreadyDecided.class));
        assertThat(reviews.inserted).isEmpty();
    }

    @Test
    void approvalPaysTheReviewerAndTheSubmitter() {
        service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(points.awarded)
                .containsExactlyInAnyOrder(
                        new PointsLedgerEntry(
                                REVIEWER,
                                SUBMISSION.id(),
                                PointsReason.REVIEW_COMPLETED,
                                ReviewService.REVIEW_POINTS,
                                NOW),
                        new PointsLedgerEntry(
                                SUBMITTER,
                                SUBMISSION.id(),
                                PointsReason.SUBMISSION_APPROVED,
                                ReviewService.APPROVAL_POINTS,
                                NOW));
    }

    @Test
    void rejectionPaysOnlyTheReviewer() {
        service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.REJECTED, Optional.of("wrong register"));

        assertThat(points.awarded).singleElement().satisfies(entry -> {
            assertThat(entry.user()).isEqualTo(REVIEWER);
            assertThat(entry.reason()).isEqualTo(PointsReason.REVIEW_COMPLETED);
        });
    }

    @Test
    void aLostRaceAndASelfReviewPayNothing() {
        workItems.transitionWins = false;

        service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());
        service.review(SUBMISSION, SUBMITTER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(points.awarded).isEmpty();
    }

    @Test
    void aTrustedApprovalIsPushedAfterTheReviewCommits() {
        var result = service.review(SUBMISSION, REVIEWER, Role.MAINTAINER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isEqualTo(Result.ok(Optional.of(new ApprovalOutcome.Synced())));
        assertThat(approvals.calls).containsExactly(SUBMISSION.id().toString());
    }

    @Test
    void anUntrustedApprovalCommitsStraightIntoAwaitingCountersignAndIsNeverPushed() {
        var result = service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isEqualTo(Result.ok(Optional.of(new ApprovalOutcome.AwaitingCountersign())));
        assertThat(workItems.calls).containsExactly("awaiting_countersign");
        assertThat(approvals.calls).isEmpty();
    }

    @Test
    void aContributorWithAnEarnedRecordIsTrustedToPush() {
        reviews.outcomes = approvals(TrustLevel.WINDOW);

        service.review(SUBMISSION, REVIEWER, Role.CONTRIBUTOR, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(workItems.calls).containsExactly("approved");
        assertThat(approvals.calls).hasSize(1);
    }

    private static List<Review> approvals(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Review(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ReviewVerdict.APPROVED,
                        Optional.empty(),
                        NOW.plusSeconds(i)))
                .toList();
    }

    @Test
    void aRejectionALostRaceAndASelfReviewNeverReachTheApprovalGate() {
        service.review(SUBMISSION, REVIEWER, Role.MAINTAINER, ReviewVerdict.REJECTED, Optional.empty());
        service.review(SUBMISSION, SUBMITTER, Role.MAINTAINER, ReviewVerdict.APPROVED, Optional.empty());
        workItems.transitionWins = false;
        service.review(SUBMISSION, REVIEWER, Role.MAINTAINER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(approvals.calls).isEmpty();
    }
}

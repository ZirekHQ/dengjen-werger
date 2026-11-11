package werger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.dao.DataAccessException;
import werger.adapters.db.ItFixture;
import werger.adapters.db.PronunciationFixRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixReview;
import werger.domain.PronunciationFixStatus;
import werger.domain.PronunciationSay;
import werger.domain.ReviewVerdict;
import werger.ports.Result;

class PronunciationFixServiceIT {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private ItFixture db;
    private PronunciationFixRepo fixes;
    private PronunciationFixService service;

    @BeforeEach
    void connect() {
        db = ItFixture.open("pronunciation-fix-it");
        fixes = new PronunciationFixRepo(db.jdbc());
        service = new PronunciationFixService(fixes, db.transactions(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private PronunciationFix submit(UUID submitter, String say) {
        return switch (service.submit("kmr", "silav", say, Optional.of("greeting"), submitter)) {
            case Result.Ok<LexiconRowError, PronunciationFix>(PronunciationFix fix) -> fix;
            case Result.Err<LexiconRowError, PronunciationFix>(LexiconRowError error) ->
                throw new AssertionError(error.toString());
        };
    }

    private int reviewCount(PronunciationFix fix) {
        return db.jdbc()
                .queryForObject(
                        "select count(*) from pronunciation_fix_reviews where fix_id = ?", Integer.class, fix.id());
    }

    @Test
    void aSubmittedFixReadsBackAsItWasWritten() {
        PronunciationFix respelling = submit(db.newUser(), "Silav");
        PronunciationFix phonemes = submit(db.newUser(), "/sɪlˈav/");

        assertThat(fixes.find(respelling.id())).contains(respelling);
        assertThat(fixes.find(phonemes.id())).contains(phonemes);
        assertThat(fixes.statusOf(respelling.id())).contains(PronunciationFixStatus.PENDING_REVIEW);
    }

    @Test
    void nextForReviewServesTheOldestOtherSpeakersFix() {
        UUID submitter = db.newUser();
        PronunciationFix older = fixes.insert(new PronunciationFix(
                UUID.randomUUID(),
                "kmr",
                "silav",
                new PronunciationSay.Respelling("Silav"),
                Optional.empty(),
                submitter,
                NOW.minusSeconds(60)));
        submit(submitter, "Selav");

        assertThat(service.nextForReview(submitter)).isEmpty();
        assertThat(service.nextForReview(db.newUser())).contains(older);
    }

    @Test
    void aSuspendedReviewerOrSubmitterIsServedNothing() {
        UUID suspended = db.newUser("suspended");
        PronunciationFix fromSuspended = submit(suspended, "Silav");

        assertThat(service.nextForReview(suspended)).isEmpty();
        assertThat(service.nextForReview(db.newUser()))
                .map(PronunciationFix::id)
                .isNotEqualTo(Optional.of(fromSuspended.id()));
    }

    @Test
    void approvalAndRejectionSetTheStatusAndRecordOneReview() {
        PronunciationFix approved = submit(db.newUser(), "Silav");
        PronunciationFix rejected = submit(db.newUser(), "Selav");

        assertThat(service.review(approved, db.newUser(), ReviewVerdict.APPROVED, Optional.empty()))
                .isInstanceOf(Result.Ok.class);
        service.review(rejected, db.newUser(), ReviewVerdict.REJECTED, Optional.of("the a is long"));

        assertThat(fixes.statusOf(approved.id())).contains(PronunciationFixStatus.APPROVED);
        assertThat(fixes.statusOf(rejected.id())).contains(PronunciationFixStatus.REJECTED);
        assertThat(reviewCount(approved)).isEqualTo(1);
        assertThat(reviewCount(rejected)).isEqualTo(1);
        assertThat(service.review(approved, db.newUser(), ReviewVerdict.REJECTED, Optional.empty()))
                .isEqualTo(Result.err(new ReviewError.AlreadyDecided()));
    }

    @Test
    void theDatabaseRejectsASelfReviewEvenIfTheServiceIsBypassed() {
        UUID submitter = db.newUser();
        PronunciationFix fix = submit(submitter, "Silav");

        assertThatThrownBy(() -> fixes.insertReview(
                        new PronunciationFixReview(fix.id(), submitter, ReviewVerdict.APPROVED, Optional.empty(), NOW)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void simultaneousReviewsOfOneFixHaveExactlyOneWinner() throws Exception {
        PronunciationFix fix = submit(db.newUser(), "Silav");
        List<UUID> reviewers = IntStream.range(0, 8).mapToObj(i -> db.newUser()).toList();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> attempts = IntStream.range(0, reviewers.size())
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        start.await();
                        ReviewVerdict verdict = i % 2 == 0 ? ReviewVerdict.APPROVED : ReviewVerdict.REJECTED;
                        return service.review(fix, reviewers.get(i), verdict, Optional.empty())
                                instanceof Result.Ok<?, ?>;
                    })
                    .toList();
            List<Future<Boolean>> futures =
                    attempts.stream().map(executor::submit).toList();
            start.countDown();
            long winners =
                    futures.stream().filter(PronunciationFixServiceIT::get).count();

            assertThat(winners).isEqualTo(1);
            assertThat(reviewCount(fix)).isEqualTo(1);
            assertThat(fixes.statusOf(fix.id())).isNotEqualTo(Optional.of(PronunciationFixStatus.PENDING_REVIEW));
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

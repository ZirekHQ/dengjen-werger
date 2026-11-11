package werger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.adapters.db.PronunciationFixRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixReview;
import werger.domain.PronunciationFixStatus;
import werger.domain.PronunciationSay;
import werger.domain.ReviewVerdict;
import werger.ports.Result;

class PronunciationFixServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final PronunciationFix FIX = new PronunciationFix(
            UUID.randomUUID(),
            "kmr",
            "AB",
            new PronunciationSay.Respelling("Yekîtiya Ewropayê"),
            Optional.empty(),
            SUBMITTER,
            NOW);

    /** A hand-rolled stub: the transitions and the race are proven against real Postgres in PronunciationFixServiceIT. */
    private static final class StubFixes extends PronunciationFixRepo {
        final List<PronunciationFix> inserted = new ArrayList<>();
        final List<PronunciationFixStatus> decided = new ArrayList<>();
        final List<PronunciationFixReview> reviews = new ArrayList<>();
        boolean transitionWins = true;

        StubFixes() {
            super(new JdbcTemplate());
        }

        @Override
        public PronunciationFix insert(PronunciationFix fix) {
            inserted.add(fix);
            return fix;
        }

        @Override
        public Optional<PronunciationFix> oldestPendingFor(UUID reviewerId) {
            return inserted.stream()
                    .filter(fix -> !fix.submitter().equals(reviewerId))
                    .findFirst();
        }

        @Override
        public boolean decide(UUID id, PronunciationFixStatus status) {
            decided.add(status);
            return transitionWins;
        }

        @Override
        public void insertReview(PronunciationFixReview review) {
            reviews.add(review);
        }
    }

    private final StubFixes fixes = new StubFixes();
    private final PronunciationFixService service =
            new PronunciationFixService(fixes, NoTransactions.template(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aValidSuggestionIsStoredWithTheParsedRowAndTheClock() {
        var result = service.submit("kmr", " AB ", "Yekîtiya  Ewropayê", Optional.of("  the EU "), SUBMITTER);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(fixes.inserted).singleElement().satisfies(fix -> {
            assertThat(fix.written()).isEqualTo("AB");
            assertThat(fix.say()).isEqualTo(new PronunciationSay.Respelling("Yekîtiya Ewropayê"));
            assertThat(fix.note()).contains("the EU");
            assertThat(fix.submitter()).isEqualTo(SUBMITTER);
            assertThat(fix.submittedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aBlankNoteIsStoredAsNoNote() {
        service.submit("kmr", "silav", "/sɪlˈav/", Optional.of("   "), SUBMITTER);

        assertThat(fixes.inserted).singleElement().satisfies(fix -> {
            assertThat(fix.say()).isEqualTo(new PronunciationSay.Phonemes("sɪlˈav"));
            assertThat(fix.note()).isEmpty();
        });
    }

    @Test
    void aBadWrittenFormOrSayIsReportedAndNothingIsStored() {
        assertThat(service.submit("kmr", "two words", "x", Optional.empty(), SUBMITTER))
                .isEqualTo(Result.err(new LexiconRowError.NotOneWord("two words")));
        assertThat(service.submit("kmr", "silav", "/sɪl", Optional.empty(), SUBMITTER))
                .isEqualTo(Result.err(new LexiconRowError.UnclosedPhonemes()));
        assertThat(fixes.inserted).isEmpty();
    }

    @Test
    void nextForReviewNeverServesTheReviewersOwnSuggestion() {
        fixes.insert(FIX);

        assertThat(service.nextForReview(SUBMITTER)).isEmpty();
        assertThat(service.nextForReview(REVIEWER)).contains(FIX);
    }

    @Test
    void aSpeakerCannotReviewTheirOwnSuggestionAndNothingIsWritten() {
        var result = service.review(FIX, SUBMITTER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isEqualTo(Result.err(new ReviewError.OwnSubmission()));
        assertThat(fixes.decided).isEmpty();
        assertThat(fixes.reviews).isEmpty();
    }

    @Test
    void theVerdictDecidesTheStatusAndTheReviewIsRecorded() {
        service.review(FIX, REVIEWER, ReviewVerdict.APPROVED, Optional.empty());
        service.review(FIX, REVIEWER, ReviewVerdict.REJECTED, Optional.of("say it with a long a"));

        assertThat(fixes.decided).containsExactly(PronunciationFixStatus.APPROVED, PronunciationFixStatus.REJECTED);
        assertThat(fixes.reviews)
                .containsExactly(
                        new PronunciationFixReview(FIX.id(), REVIEWER, ReviewVerdict.APPROVED, Optional.empty(), NOW),
                        new PronunciationFixReview(
                                FIX.id(), REVIEWER, ReviewVerdict.REJECTED, Optional.of("say it with a long a"), NOW));
    }

    @Test
    void aLostRaceReportsAlreadyDecidedAndRecordsNoReview() {
        fixes.transitionWins = false;

        var result = service.review(FIX, REVIEWER, ReviewVerdict.APPROVED, Optional.empty());

        assertThat(result).isEqualTo(Result.err(new ReviewError.AlreadyDecided()));
        assertThat(fixes.reviews).isEmpty();
    }
}

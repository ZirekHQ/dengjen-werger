package werger.service;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import werger.adapters.db.PronunciationFixRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixReview;
import werger.domain.PronunciationFixStatus;
import werger.domain.PronunciationSay;
import werger.domain.ReviewVerdict;
import werger.ports.Result;

/**
 * Takes a native speaker's pronunciation fix and serves it to a second speaker for review, with the same rule as
 * translations: nobody reviews their own suggestion, and of two racing reviewers only one decides.
 */
public class PronunciationFixService {
    private final PronunciationFixRepo fixes;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PronunciationFixService(PronunciationFixRepo fixes, TransactionTemplate transactions, Clock clock) {
        this.fixes = fixes;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Checks the volunteer's input against the lexicon rules and stores it for review. The caller must already have
     * checked that {@code submitter} is an active user.
     */
    public Result<LexiconRowError, PronunciationFix> submit(
            String language, String written, String say, Optional<String> note, UUID submitter) {
        return switch (LexiconRow.parseWritten(written)) {
            case Result.Err<LexiconRowError, String>(LexiconRowError error) -> Result.err(error);
            case Result.Ok<LexiconRowError, String>(String word) ->
                switch (LexiconRow.parseSay(say)) {
                    case Result.Err<LexiconRowError, PronunciationSay>(LexiconRowError error) -> Result.err(error);
                    case Result.Ok<LexiconRowError, PronunciationSay>(PronunciationSay parsed) ->
                        Result.ok(fixes.insert(new PronunciationFix(
                                UUID.randomUUID(),
                                language,
                                word,
                                parsed,
                                note.map(String::strip).filter(text -> !text.isEmpty()),
                                submitter,
                                clock.instant())));
                };
        };
    }

    public Optional<PronunciationFix> nextForReview(UUID reviewerId) {
        return fixes.oldestPendingFor(reviewerId);
    }

    /** Records the verdict. The caller must already have checked that {@code reviewer} is an active user. */
    public Result<ReviewError, Void> review(
            PronunciationFix fix, UUID reviewer, ReviewVerdict verdict, Optional<String> comment) {
        if (fix.submitter().equals(reviewer)) {
            return Result.err(new ReviewError.OwnSubmission());
        }
        PronunciationFixReview review =
                new PronunciationFixReview(fix.id(), reviewer, verdict, comment, clock.instant());
        return Objects.requireNonNull(transactions.execute(status -> decide(status, review)));
    }

    // The status-gated transition runs first so that of two racing reviewers only one reaches the review insert.
    private Result<ReviewError, Void> decide(TransactionStatus status, PronunciationFixReview review) {
        PronunciationFixStatus outcome = review.verdict() == ReviewVerdict.APPROVED
                ? PronunciationFixStatus.APPROVED
                : PronunciationFixStatus.REJECTED;
        if (!fixes.decide(review.fix(), outcome)) {
            status.setRollbackOnly();
            return Result.err(new ReviewError.AlreadyDecided());
        }
        fixes.insertReview(review);
        return Result.ok(null);
    }
}

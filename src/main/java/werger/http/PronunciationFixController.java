package werger.http;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import werger.adapters.db.PronunciationFixRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationSay;
import werger.domain.ReviewVerdict;
import werger.domain.User;
import werger.service.LexiconRowError;
import werger.service.PronunciationFixService;
import werger.service.ReviewError;

/**
 * The listening page's calls: suggest how a word should sound, and review another speaker's suggestion. The review
 * queue never says who made a suggestion, so a reviewer judges the sound, not the person.
 */
@RestController
public class PronunciationFixController {
    private final PronunciationFixService service;
    private final PronunciationFixRepo fixes;

    /** What the volunteer typed on "sounds wrong". {@code note} may be null. */
    public record Suggestion(String language, String written, String say, String note) {}

    /** A suggestion as the page shows it. {@code note} is null when there is none. */
    public record FixView(UUID id, String language, String written, String sayKind, String say, String note) {}

    /** A reviewer's decision. {@code comment} may be null. */
    public record Verdict(ReviewVerdict verdict, String comment) {}

    /** Why a call was refused: a stable code for the page and a detail for people. */
    public record Problem(String error, String detail) {}

    /**
     * Serves fixes through {@code service}, reading single fixes from {@code fixes}.
     *
     * @param service submitting and reviewing fixes
     * @param fixes the fix repository
     */
    public PronunciationFixController(PronunciationFixService service, PronunciationFixRepo fixes) {
        this.service = service;
        this.fixes = fixes;
    }

    /**
     * Stores a suggestion for review.
     *
     * @param user the signed-in volunteer
     * @param suggestion what they typed
     * @return 201 with the stored fix, or 422 saying what to correct
     */
    @PostMapping("/pronunciation-fixes")
    public ResponseEntity<Object> suggest(User user, @RequestBody Suggestion suggestion) {
        return service.submit(
                        Optional.ofNullable(suggestion.language()).orElse(""),
                        Optional.ofNullable(suggestion.written()).orElse(""),
                        Optional.ofNullable(suggestion.say()).orElse(""),
                        Optional.ofNullable(suggestion.note()),
                        user.id())
                .fold(
                        error -> ResponseEntity.unprocessableContent().body(problem(error)),
                        fix -> ResponseEntity.status(HttpStatus.CREATED).body(view(fix)));
    }

    /**
     * The oldest suggestion waiting for review that the volunteer did not make.
     *
     * @param user the signed-in reviewer
     * @return 200 with the fix, or 204 when nothing is waiting
     */
    @GetMapping("/pronunciation-fixes/next")
    public ResponseEntity<FixView> next(User user) {
        return service.nextForReview(user.id())
                .map(fix -> ResponseEntity.ok(view(fix)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Records a reviewer's verdict on a suggestion.
     *
     * @param user the signed-in reviewer
     * @param id the fix
     * @param verdict the decision
     * @return 204, 404 for an unknown fix, 403 for one's own, 409 if another reviewer decided first
     */
    @PostMapping("/pronunciation-fixes/{id}/review")
    public ResponseEntity<Object> review(User user, @PathVariable UUID id, @RequestBody Verdict verdict) {
        if (verdict.verdict() == null) {
            return ResponseEntity.unprocessableContent()
                    .body(new Problem("missing-verdict", "verdict must be APPROVED or REJECTED"));
        }
        Optional<PronunciationFix> fix = fixes.find(id);
        if (fix.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return service.review(fix.get(), user.id(), verdict.verdict(), Optional.ofNullable(verdict.comment()))
                .fold(
                        PronunciationFixController::refused,
                        done -> ResponseEntity.noContent().build());
    }

    /**
     * The only constraint a valid suggestion can break on insert is the language reference.
     *
     * @param e the database's refusal
     * @return 422 naming the unknown language
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Problem> unknownLanguage(DataIntegrityViolationException e) {
        return ResponseEntity.unprocessableContent()
                .body(new Problem("unknown-language", "the language is not one Werger works on"));
    }

    /**
     * The response for a review the service refused.
     *
     * @param error why
     * @return 403 for one's own suggestion, 409 if already decided
     */
    private static ResponseEntity<Object> refused(ReviewError error) {
        return switch (error) {
            case ReviewError.OwnSubmission _ ->
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new Problem("own-submission", "nobody reviews their own suggestion"));
            case ReviewError.AlreadyDecided _ ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new Problem("already-decided", "another reviewer decided this suggestion first"));
        };
    }

    /**
     * What the volunteer should correct.
     *
     * @param error why the input cannot become a lexicon row
     * @return the problem to show
     */
    static Problem problem(LexiconRowError error) {
        return switch (error) {
            case LexiconRowError.EmptyWritten _ -> new Problem("empty-written", "type the word as it is written");
            case LexiconRowError.NotOneWord(String written) ->
                new Problem("not-one-word", "\"" + written + "\" must be a single word of letters only");
            case LexiconRowError.EmptySay _ -> new Problem("empty-say", "type how the word should sound");
            case LexiconRowError.UnclosedPhonemes _ ->
                new Problem("unclosed-phonemes", "phonemes need a slash at both ends");
            case LexiconRowError.EmptyPhonemes _ -> new Problem("empty-phonemes", "type phonemes between the slashes");
            case LexiconRowError.ForbiddenCharacters(String characters) ->
                new Problem("forbidden-characters", "remove these characters: " + characters);
        };
    }

    /**
     * A fix as the page shows it, without the submitter.
     *
     * @param fix the stored fix
     * @return the view
     */
    static FixView view(PronunciationFix fix) {
        String kind = switch (fix.say()) {
            case PronunciationSay.Respelling _ -> "respelling";
            case PronunciationSay.Phonemes _ -> "phonemes";
        };
        String say = switch (fix.say()) {
            case PronunciationSay.Respelling(String words) -> words;
            case PronunciationSay.Phonemes(String ipa) -> ipa;
        };
        return new FixView(
                fix.id(), fix.language(), fix.written(), kind, say, fix.note().orElse(null));
    }
}

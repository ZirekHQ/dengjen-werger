package werger.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;
import werger.adapters.db.PronunciationFixRepo;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixStatus;
import werger.ports.LexiconTarget;
import werger.ports.Result;
import werger.ports.SourceError;

/**
 * Brings approved pronunciation fixes into dengjen's lexicon through one pull request per language. Each run starts
 * from the lexicon on the default branch, so a merged pull request settles its fixes and the next run proposes only
 * what is still missing.
 */
public class LexiconPublisher {
    private static final Pattern BACKTICKS = Pattern.compile("`+");

    private final PronunciationFixRepo fixes;
    private final LexiconTarget target;
    private final TransactionTemplate transactions;

    public LexiconPublisher(PronunciationFixRepo fixes, LexiconTarget target, TransactionTemplate transactions) {
        this.fixes = fixes;
        this.target = target;
        this.transactions = transactions;
    }

    public Result<SourceError, LexiconPublication> publish(String language) {
        return target.current(language)
                .<Result<SourceError, LexiconPublication>>fold(Result::err, current -> publishOnto(language, current));
    }

    private Result<SourceError, LexiconPublication> publishOnto(String language, String current) {
        List<PronunciationFix> approved = fixes.approvedAwaitingLanding(language);
        // Approval order, so a later fix for the same written form replaces an earlier one.
        Map<String, PronunciationFix> latest = new LinkedHashMap<>();
        approved.forEach(fix -> latest.put(fix.written(), fix));
        List<PronunciationFix> superseded = approved.stream()
                .filter(fix -> !latest.get(fix.written()).id().equals(fix.id()))
                .toList();
        Map<Boolean, List<PronunciationFix>> inLexicon =
                latest.values().stream().collect(Collectors.partitioningBy(fix -> LexiconFile.contains(current, fix)));
        transactions.executeWithoutResult(status -> {
            superseded.forEach(fix -> fixes.settle(fix.id(), PronunciationFixStatus.SUPERSEDED));
            inLexicon.get(true).forEach(fix -> fixes.settle(fix.id(), PronunciationFixStatus.LANDED));
        });
        List<PronunciationFix> missing = inLexicon.get(false);
        if (missing.isEmpty()) {
            return Result.ok(new LexiconPublication.UpToDate());
        }
        List<UUID> ids = missing.stream().map(PronunciationFix::id).toList();
        return target.propose(language, LexiconFile.apply(current, missing), description(missing))
                .<Result<SourceError, LexiconPublication>>fold(
                        Result::err, pullRequest -> Result.ok(new LexiconPublication.Proposed(pullRequest, ids)));
    }

    /**
     * The rows go in a code block because a volunteer's note is free text: outside one, an {@code @name} in it would
     * notify that GitHub user. The fence is longer than any run of backticks in the rows, so a note cannot close it.
     */
    static String description(List<PronunciationFix> missing) {
        String rows = missing.stream().map(LexiconRow::render).collect(Collectors.joining("\n"));
        int longestRun = BACKTICKS
                .matcher(rows)
                .results()
                .mapToInt(match -> match.end() - match.start())
                .max()
                .orElse(0);
        String fence = "`".repeat(Math.max(3, longestRun + 1));
        return "Pronunciation fixes suggested by native speakers in Werger. A second speaker approved each one.\n\n"
                + fence + "\n" + rows + "\n" + fence + "\n";
    }
}

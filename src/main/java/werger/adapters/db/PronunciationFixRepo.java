package werger.adapters.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixReview;
import werger.domain.PronunciationFixStatus;
import werger.domain.PronunciationSay;

public class PronunciationFixRepo {
    private final JdbcTemplate jdbc;

    public PronunciationFixRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** New fixes always wait for review. */
    public PronunciationFix insert(PronunciationFix fix) {
        String kind = switch (fix.say()) {
            case PronunciationSay.Respelling _ -> "respelling";
            case PronunciationSay.Phonemes _ -> "phonemes";
        };
        String say = switch (fix.say()) {
            case PronunciationSay.Respelling(String words) -> words;
            case PronunciationSay.Phonemes(String ipa) -> ipa;
        };
        jdbc.update(
                """
                insert into pronunciation_fixes
                  (id, language_code, written, say_kind, say, note, submitter_id, submitted_at)
                values (?, ?, ?, ?::pronunciation_say_kind, ?, ?, ?, ?)
                """,
                fix.id(),
                fix.language(),
                fix.written(),
                kind,
                say,
                fix.note().orElse(null),
                fix.submitter(),
                OffsetDateTime.ofInstant(fix.submittedAt(), ZoneOffset.UTC));
        return fix;
    }

    public Optional<PronunciationFix> find(UUID id) {
        return jdbc.query("select * from pronunciation_fixes where id = ?", PronunciationFixRepo::toFix, id).stream()
                .findFirst();
    }

    public Optional<PronunciationFixStatus> statusOf(UUID id) {
        return jdbc
                .query(
                        "select status::text from pronunciation_fixes where id = ?",
                        (row, n) ->
                                PronunciationFixStatus.valueOf(row.getString(1).toUpperCase(Locale.ROOT)),
                        id)
                .stream()
                .findFirst();
    }

    /**
     * The earliest fix awaiting review, never the reviewer's own and never one from a suspended user, so reviewers are
     * served suggestions in order instead of picking them.
     */
    public Optional<PronunciationFix> oldestPendingFor(UUID reviewerId) {
        return jdbc.query("""
                        select f.*
                        from pronunciation_fixes f
                        join users submitter on submitter.id = f.submitter_id and submitter.status = 'active'
                        where f.status = 'pending_review'
                          and f.submitter_id <> ?
                          and exists (select 1 from users r where r.id = ? and r.status = 'active')
                        order by f.submitted_at, f.id
                        limit 1
                        """, PronunciationFixRepo::toFix, reviewerId, reviewerId).stream()
                .findFirst();
    }

    /**
     * Moves a pending fix to {@code status}. Returns true only for the caller whose UPDATE matched a row: the
     * {@code status = 'pending_review'} predicate is what lets only one of two racing reviewers decide it.
     */
    public boolean decide(UUID id, PronunciationFixStatus status) {
        return jdbc.update("""
                        update pronunciation_fixes set status = ?::pronunciation_fix_status
                        where id = ? and status = 'pending_review'
                        """, status.name().toLowerCase(Locale.ROOT), id) == 1;
    }

    /**
     * Approved fixes in {@code language} not yet compared with the lexicon, in the order they were approved. The join
     * on a second speaker's approving review means a fix can reach the lexicon only through one.
     */
    public List<PronunciationFix> approvedAwaitingLanding(String language) {
        return jdbc.query("""
                        select f.*
                        from pronunciation_fixes f
                        join pronunciation_fix_reviews r
                          on r.fix_id = f.id and r.verdict = 'approved' and r.reviewer_id <> f.submitter_id
                        where f.status = 'approved' and f.language_code = ?
                        order by r.reviewed_at, f.id
                        """, PronunciationFixRepo::toFix, language);
    }

    /** Moves an approved fix to {@code status}; a fix already settled stays as it is. */
    public void settle(UUID id, PronunciationFixStatus status) {
        jdbc.update("""
                update pronunciation_fixes set status = ?::pronunciation_fix_status
                where id = ? and status = 'approved'
                """, status.name().toLowerCase(Locale.ROOT), id);
    }

    /** Throws if the reviewer suggested the fix: the {@code ..._reviewer_not_submitter} trigger is the backstop. */
    public void insertReview(PronunciationFixReview review) {
        jdbc.update(
                """
                insert into pronunciation_fix_reviews (fix_id, reviewer_id, verdict, comment, reviewed_at)
                values (?, ?, ?::review_verdict, ?, ?)
                """,
                review.fix(),
                review.reviewer(),
                review.verdict().name().toLowerCase(Locale.ROOT),
                review.comment().orElse(null),
                OffsetDateTime.ofInstant(review.reviewedAt(), ZoneOffset.UTC));
    }

    /** Rebuilds the sealed suggestion from {@code say_kind}, which the schema limits to respelling or phonemes. */
    static PronunciationFix toFix(ResultSet row, int rowNum) throws SQLException {
        String say = row.getString("say");
        PronunciationSay parsed = "phonemes".equals(row.getString("say_kind"))
                ? new PronunciationSay.Phonemes(say)
                : new PronunciationSay.Respelling(say);
        return new PronunciationFix(
                row.getObject("id", UUID.class),
                row.getString("language_code"),
                row.getString("written"),
                parsed,
                Optional.ofNullable(row.getString("note")),
                row.getObject("submitter_id", UUID.class),
                row.getObject("submitted_at", OffsetDateTime.class).toInstant());
    }
}

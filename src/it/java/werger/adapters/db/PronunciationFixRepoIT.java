package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import werger.domain.PronunciationFix;
import werger.domain.PronunciationFixReview;
import werger.domain.PronunciationFixStatus;
import werger.domain.PronunciationSay;
import werger.domain.ReviewVerdict;

class PronunciationFixRepoIT {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private ItFixture db;
    private PronunciationFixRepo fixes;
    private String language;

    @BeforeEach
    void connect() {
        db = ItFixture.open("pronunciation-fix-repo-it");
        fixes = new PronunciationFixRepo(db.jdbc());
        language = db.newLanguage();
    }

    @AfterEach
    void cleanUp() {
        if (db != null) {
            db.close();
        }
    }

    private PronunciationFix pending(String inLanguage, String written) {
        return fixes.insert(new PronunciationFix(
                UUID.randomUUID(),
                inLanguage,
                written,
                new PronunciationSay.Respelling("Silav"),
                Optional.empty(),
                db.newUser(),
                NOW));
    }

    private PronunciationFix reviewed(String written, ReviewVerdict verdict, Instant reviewedAt) {
        PronunciationFix fix = pending(language, written);
        fixes.decide(
                fix.id(),
                verdict == ReviewVerdict.APPROVED ? PronunciationFixStatus.APPROVED : PronunciationFixStatus.REJECTED);
        fixes.insertReview(new PronunciationFixReview(fix.id(), db.newUser(), verdict, Optional.empty(), reviewedAt));
        return fix;
    }

    @Test
    void awaitingLandingListsApprovedFixesOfTheLanguageInApprovalOrder() {
        PronunciationFix second = reviewed("silav", ReviewVerdict.APPROVED, NOW.plusSeconds(60));
        PronunciationFix first = reviewed("AB", ReviewVerdict.APPROVED, NOW);
        reviewed("NY", ReviewVerdict.REJECTED, NOW);
        pending(language, "pending");
        pending("kmr", "other-language");

        assertThat(fixes.approvedAwaitingLanding(language)).containsExactly(first, second);
    }

    @Test
    void anApprovedStatusWithoutAnApprovingReviewNeverReachesTheLexicon() {
        PronunciationFix unreviewed = pending(language, "AB");
        fixes.decide(unreviewed.id(), PronunciationFixStatus.APPROVED);

        assertThat(fixes.approvedAwaitingLanding(language)).isEmpty();
    }

    @Test
    void settlingMovesAnApprovedFixOutOfTheQueueAndLeavesOthersAlone() {
        PronunciationFix landed = reviewed("AB", ReviewVerdict.APPROVED, NOW);
        PronunciationFix rejected = reviewed("NY", ReviewVerdict.REJECTED, NOW);

        fixes.settle(landed.id(), PronunciationFixStatus.LANDED);
        fixes.settle(rejected.id(), PronunciationFixStatus.LANDED);
        fixes.settle(landed.id(), PronunciationFixStatus.SUPERSEDED);

        assertThat(fixes.statusOf(landed.id())).contains(PronunciationFixStatus.LANDED);
        assertThat(fixes.statusOf(rejected.id())).contains(PronunciationFixStatus.REJECTED);
        assertThat(fixes.approvedAwaitingLanding(language)).isEmpty();
    }
}

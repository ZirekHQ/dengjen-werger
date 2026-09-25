package werger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import werger.domain.PointsReason;

class DraftCreditTest {
    @Test
    void noDraftAtSubmissionTimeAlwaysYieldsSubmissionApproved() {
        assertThat(DraftCredit.pointsReasonFor("Temam", Optional.empty(), 1.0))
                .isEqualTo(PointsReason.SUBMISSION_APPROVED);
    }

    @Test
    void anExactMatchAtThresholdOneYieldsDraftConfirmed() {
        assertThat(DraftCredit.pointsReasonFor("Temam", Optional.of("Temam"), 1.0))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
    }

    @Test
    void anyEditAtThresholdOneYieldsSubmissionApproved() {
        assertThat(DraftCredit.pointsReasonFor("Temam.", Optional.of("Temam"), 1.0))
                .isEqualTo(PointsReason.SUBMISSION_APPROVED);
    }

    @Test
    void whitespaceAndUnicodeNormalizationDifferencesAloneDontCountAsAnEdit() {
        String precomposed = "Têmam"; // precomposed ê as a single code point
        String decomposed = "Têmam"; // e + combining circumflex
        assertThat(DraftCredit.pointsReasonFor("  " + decomposed + "  ", Optional.of(precomposed), 1.0))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
    }

    @Test
    void aLoweredThresholdToleratesASmallEditAsStillConfirmed() {
        assertThat(DraftCredit.pointsReasonFor("Temam.", Optional.of("Temam"), 0.8))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
    }

    @Test
    void anOutOfRangeMatchThresholdIsRejectedRatherThanSilentlyMisScoringEverySubmission() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DraftCredit.pointsReasonFor("Temam", Optional.of("Temam"), -1.0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DraftCredit.pointsReasonFor("Temam", Optional.of("Temam"), 1.5));
    }
}

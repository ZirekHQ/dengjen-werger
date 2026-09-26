package werger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import java.util.Random;
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
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DraftCredit.pointsReasonFor("Temam", Optional.of("Temam"), Double.NaN));
    }

    @Test
    void unicodeWhitespaceAloneDoesntCountAsAnEdit() {
        assertThat(DraftCredit.pointsReasonFor("Temam\u2003", Optional.of("Temam"), 1.0))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
    }

    @Test
    void theThresholdIsAppliedToNormalizedEditDistance() {
        // kitten -> sitting is 3 edits over 7 characters: similarity 4/7 ≈ 0.571.
        assertThat(DraftCredit.pointsReasonFor("kitten", Optional.of("sitting"), 0.57))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
        assertThat(DraftCredit.pointsReasonFor("kitten", Optional.of("sitting"), 0.58))
                .isEqualTo(PointsReason.SUBMISSION_APPROVED);
    }

    @Test
    void textsLongerThanTheScoringCapAreConfirmedOnlyWhenUnchanged() {
        String longDraft = "a".repeat(DraftCredit.MAX_SCORED_LENGTH + 1);
        String oneEditAway = "b" + longDraft.substring(1);
        assertThat(DraftCredit.pointsReasonFor(longDraft, Optional.of(longDraft), 0.5))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
        assertThat(DraftCredit.pointsReasonFor(oneEditAway, Optional.of(longDraft), 0.5))
                .isEqualTo(PointsReason.SUBMISSION_APPROVED);
    }

    @Test
    void textsAtTheScoringCapAreStillScoredByEditDistance() {
        String draft = "a".repeat(DraftCredit.MAX_SCORED_LENGTH);
        String oneEditAway = "b" + draft.substring(1);
        assertThat(DraftCredit.pointsReasonFor(oneEditAway, Optional.of(draft), 0.5))
                .isEqualTo(PointsReason.DRAFT_CONFIRMED);
    }

    @Test
    void boundedDistanceDecidesExactlyLikeFullLevenshtein() {
        Random random = new Random(42);
        for (int n = 0; n < 5_000; n++) {
            String a = randomText(random);
            String b = random.nextBoolean() ? mutate(a, random) : randomText(random);
            double threshold = random.nextInt(21) / 20.0;
            int maxLen = Math.max(a.length(), b.length());
            boolean expected = maxLen == 0 || 1.0 - (double) levenshtein(a, b) / maxLen >= threshold;
            assertThat(DraftCredit.pointsReasonFor(a, Optional.of(b), threshold))
                    .as("%s vs %s at %s", a, b, threshold)
                    .isEqualTo(expected ? PointsReason.DRAFT_CONFIRMED : PointsReason.SUBMISSION_APPROVED);
        }
    }

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(12);
        for (int i = 0; i < length; i++) {
            text.append((char) ('a' + random.nextInt(3)));
        }
        return text.toString();
    }

    private static String mutate(String text, Random random) {
        StringBuilder mutated = new StringBuilder(text);
        int edits = random.nextInt(4);
        for (int e = 0; e < edits; e++) {
            int at = mutated.isEmpty() ? 0 : random.nextInt(mutated.length());
            switch (random.nextInt(3)) {
                case 0 -> mutated.insert(at, 'c');
                case 1 -> {
                    if (!mutated.isEmpty()) {
                        mutated.deleteCharAt(at);
                    }
                }
                default -> {
                    if (!mutated.isEmpty()) {
                        mutated.setCharAt(at, 'b');
                    }
                }
            }
        }
        return mutated.toString();
    }

    private static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int substitution = d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                d[i][j] = Math.min(substitution, Math.min(d[i - 1][j], d[i][j - 1]) + 1);
            }
        }
        return d[a.length()][b.length()];
    }
}

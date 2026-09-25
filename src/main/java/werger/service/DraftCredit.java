package werger.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Optional;
import werger.domain.PointsReason;

/**
 * Decides whether an approved submission represents real work or a near-untouched machine-translation draft, so
 * points-awarding code doesn't have to know how a submission's origin is tracked.
 */
public final class DraftCredit {
    /**
     * Longest normalized text scored by edit distance. The banded distance still grows with length times band width,
     * so without a cap a long, similar pair at a low threshold costs quadratic work. Translation units (UI strings) are
     * far shorter; beyond this only an unchanged draft counts as confirmed, which bounds the work to about
     * {@code MAX_SCORED_LENGTH}² cells.
     */
    static final int MAX_SCORED_LENGTH = 2_000;

    private DraftCredit() {}

    public static PointsReason pointsReasonFor(
            String proposedTranslation, Optional<String> draftAtSubmission, double matchThreshold) {
        if (!Double.isFinite(matchThreshold) || matchThreshold < 0.0 || matchThreshold > 1.0) {
            throw new IllegalArgumentException("matchThreshold must be within [0.0, 1.0], got " + matchThreshold);
        }
        boolean confirmed = draftAtSubmission
                .filter(draft -> confirms(proposedTranslation, draft, matchThreshold))
                .isPresent();
        return confirmed ? PointsReason.DRAFT_CONFIRMED : PointsReason.SUBMISSION_APPROVED;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).strip();
    }

    // Confirmed when 1 - distance / maxLen >= threshold. Only distances up to (1 - threshold) * maxLen can
    // confirm, so the edit distance is computed within that bound and gives up as soon as it is exceeded.
    private static boolean confirms(String proposed, String draft, double threshold) {
        String a = normalize(proposed);
        String b = normalize(draft);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0 || threshold == 0.0) {
            return true;
        }
        if (maxLen > MAX_SCORED_LENGTH) {
            return a.equals(b);
        }
        int bound = (int) Math.floor((1.0 - threshold) * maxLen) + 1;
        int distance = boundedLevenshtein(a, b, bound);
        return distance <= bound && 1.0 - (double) distance / maxLen >= threshold;
    }

    /** The Levenshtein distance between a and b when it is at most bound, otherwise bound + 1. */
    private static int boundedLevenshtein(String a, String b, int bound) {
        int over = bound + 1;
        if (Math.abs(a.length() - b.length()) > bound) {
            return over;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        Arrays.fill(prev, over);
        Arrays.fill(curr, over);
        for (int j = 0; j <= Math.min(b.length(), bound); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            // Only cells within `bound` of the diagonal can stay within the bound.
            int lo = Math.max(1, i - bound);
            int hi = Math.min(b.length(), i + bound);
            curr[0] = i <= bound ? i : over;
            if (lo > 1) {
                curr[lo - 1] = over;
            }
            int rowMin = curr[0];
            for (int j = lo; j <= hi; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
                curr[j] = Math.min(cost, over);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > bound) {
                return over;
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}

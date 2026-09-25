package werger.service;

import java.text.Normalizer;
import java.util.Optional;
import werger.domain.PointsReason;

/**
 * Decides whether an approved submission represents real work or a near-untouched machine-translation draft, so
 * points-awarding code doesn't have to know how a submission's origin is tracked.
 */
public final class DraftCredit {
    private DraftCredit() {}

    public static PointsReason pointsReasonFor(
            String proposedTranslation, Optional<String> draftAtSubmission, double matchThreshold) {
        if (matchThreshold < 0.0 || matchThreshold > 1.0) {
            throw new IllegalArgumentException("matchThreshold must be within [0.0, 1.0], got " + matchThreshold);
        }
        boolean confirmed = draftAtSubmission
                .filter(draft -> similarity(proposedTranslation, draft) >= matchThreshold)
                .isPresent();
        return confirmed ? PointsReason.DRAFT_CONFIRMED : PointsReason.SUBMISSION_APPROVED;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).trim();
    }

    private static double similarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        int maxLen = Math.max(na.length(), nb.length());
        return maxLen == 0 ? 1.0 : 1.0 - (double) levenshtein(na, nb) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 0; i < a.length(); i++) {
            curr[0] = i + 1;
            for (int j = 0; j < b.length(); j++) {
                curr[j + 1] =
                        a.charAt(i) == b.charAt(j) ? prev[j] : 1 + Math.min(prev[j], Math.min(prev[j + 1], curr[j]));
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}

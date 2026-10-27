package werger.adapters.crowdin;

import java.util.Set;

/**
 * One translation of a Crowdin string. A plural form also carries {@code pluralRow}, the ids of every form submitted
 * together with it; a plain translation leaves it empty.
 */
public record CrowdinTranslation(long translationId, long stringId, String text, Set<Long> pluralRow) {
    public CrowdinTranslation(long translationId, long stringId, String text) {
        this(translationId, stringId, text, Set.of());
    }

    boolean isPlural() {
        return !pluralRow.isEmpty();
    }

    Set<Long> idsToApprove() {
        return isPlural() ? pluralRow : Set.of(translationId);
    }
}

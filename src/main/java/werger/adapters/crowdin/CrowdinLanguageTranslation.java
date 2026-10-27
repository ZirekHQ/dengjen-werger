package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One row of Crowdin's List Language Translations response: a plain translation, or a plural one whose forms each
 * carry their own {@code translationId} and {@code text}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CrowdinLanguageTranslation(Optional<Long> translationId, long stringId, String text, List<Plural> plurals) {
    CrowdinLanguageTranslation {
        plurals = plurals == null ? List.of() : plurals;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Plural(long translationId, String text) {}

    /** A row without a translation id is not a string translation, so it yields nothing. */
    List<CrowdinTranslation> flatten() {
        if (plurals.isEmpty()) {
            return translationId
                    .map(id -> List.of(new CrowdinTranslation(id, stringId, text)))
                    .orElse(List.of());
        }
        Set<Long> row = plurals.stream().map(Plural::translationId).collect(Collectors.toSet());
        return plurals.stream()
                .map(form -> new CrowdinTranslation(form.translationId(), stringId, form.text(), row))
                .toList();
    }
}

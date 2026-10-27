package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;

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

    List<CrowdinTranslation> flatten() {
        if (plurals.isEmpty()) {
            return List.of(new CrowdinTranslation(translationId.orElseThrow(), stringId, text));
        }
        return plurals.stream()
                .map(form -> new CrowdinTranslation(form.translationId(), stringId, form.text()))
                .toList();
    }
}

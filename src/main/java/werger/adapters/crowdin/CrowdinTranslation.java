package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One item of Crowdin's List Language Translations response, which names its id {@code translationId}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CrowdinTranslation(long translationId, long stringId, String text) {}

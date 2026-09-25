package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrowdinTranslation(long id, long stringId, String text) {}

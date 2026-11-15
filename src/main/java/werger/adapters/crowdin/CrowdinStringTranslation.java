package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record CrowdinStringTranslation(long id, String text) {}

package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrowdinCreatedTranslation(long id) {}

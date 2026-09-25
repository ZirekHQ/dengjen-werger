package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrowdinSourceString(long id, String text, String identifier) {}

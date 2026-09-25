package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrowdinApproval(long translationId) {}

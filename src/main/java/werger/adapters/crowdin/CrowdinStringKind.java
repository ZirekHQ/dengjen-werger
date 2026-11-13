package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The part of a Crowdin source string that tells a plural string from a plain one; {@code text} is skipped. */
@JsonIgnoreProperties(ignoreUnknown = true)
record CrowdinStringKind(boolean hasPlurals) {}

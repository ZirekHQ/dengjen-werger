package werger.adapters.crowdin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Crowdin's list response shape: {@code {"data": [{"data": {...}}, ...]}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record CrowdinEnvelope<A>(List<Wrapped<A>> data) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Wrapped<A>(A data) {}
}

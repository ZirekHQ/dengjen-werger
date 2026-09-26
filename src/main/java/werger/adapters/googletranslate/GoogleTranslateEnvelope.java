package werger.adapters.googletranslate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleTranslateEnvelope(GoogleTranslateData data) {}

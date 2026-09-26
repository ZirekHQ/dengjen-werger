package werger.adapters.googletranslate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleTranslateData(List<GoogleTranslation> translations) {}

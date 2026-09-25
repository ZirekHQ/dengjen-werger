package werger.adapters.googletranslate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GoogleTranslateCodecsTest {
    @Test
    void decodesGoogleTranslatesV2ResponseEnvelope() {
        String json = """
                {"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}""";
        GoogleTranslateEnvelope envelope = JsonMapper.builder().build().readValue(json, GoogleTranslateEnvelope.class);
        assertThat(envelope.data().translations())
                .extracting(GoogleTranslation::translatedText)
                .containsExactly("Temam", "Na");
    }
}

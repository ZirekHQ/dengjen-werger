package werger.adapters.crowdin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CrowdinCodecsTest {
    @Test
    void decodesACrowdinSourceStringResponseIgnoringUnknownFields() {
        String json = """
                {"id": 661, "text": "OK", "identifier": "addon.OK", "projectId": 780748}""";
        assertThat(JsonMapper.builder()
                        .build()
                        .readValue(json, CrowdinSourceString.class)
                        .text())
                .isEqualTo("OK");
    }
}

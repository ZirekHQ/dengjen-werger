package werger.adapters.googletranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import werger.domain.Language;
import werger.ports.MtError;
import werger.ports.Result;

class GoogleTranslateSourceTest {
    private static final String ENDPOINT = "https://translation.googleapis.com/language/translate/v2";
    private static final Language KURMANJI = new Language("kmr", "Kurmanji Kurdish");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final GoogleTranslateSource source =
            new GoogleTranslateSource(new GoogleTranslateClient(builder.build(), "test-key"));

    private static MtError errorOf(Result<MtError, Map<String, String>> result) {
        assertThat(result).isInstanceOf(Result.Err.class);
        return result.fold(error -> error, value -> null);
    }

    @Test
    void translateBatchMapsEachSourceTextToItsTranslation() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.target").value("ku"))
                .andRespond(withSuccess("""
                        {"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}""", MediaType.APPLICATION_JSON));
        assertThat(source.translateBatch(KURMANJI, List.of("OK", "No")))
                .isEqualTo(Result.ok(Map.of("OK", "Temam", "No", "Na")));
    }

    @Test
    void translateBatchReturnsUnsupportedWithoutCallingTheProviderForAnUnmappedLanguage() {
        server.expect(never(), requestTo(ENDPOINT));
        assertThat(errorOf(source.translateBatch(new Language("xx", "Unmapped"), List.of("OK"))))
                .isInstanceOf(MtError.Unsupported.class);
        server.verify();
    }

    @Test
    void translateBatchMapsA400ResponseToUnsupported() {
        server.expect(requestTo(ENDPOINT)).andRespond(withBadRequest().body("""
                {"error":{"code":400,"message":"invalid target"}}"""));
        assertThat(errorOf(source.translateBatch(KURMANJI, List.of("OK")))).isInstanceOf(MtError.Unsupported.class);
    }

    @Test
    void translateBatchMapsA429ResponseToTransient() {
        server.expect(requestTo(ENDPOINT)).andRespond(withTooManyRequests().body("""
                {"error":{"code":429,"message":"rate limited"}}"""));
        assertThat(errorOf(source.translateBatch(KURMANJI, List.of("OK")))).isInstanceOf(MtError.Transient.class);
    }

    @Test
    void translateBatchMapsA200ResponseWithAnUnexpectedShapeToUnsupported() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"unexpected": "shape"}""", MediaType.APPLICATION_JSON));
        assertThat(errorOf(source.translateBatch(KURMANJI, List.of("OK")))).isInstanceOf(MtError.Unsupported.class);
    }

    @Test
    void translateBatchMapsATranslationCountMismatchToUnsupported() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":{"translations":[]}}""", MediaType.APPLICATION_JSON));
        assertThat(errorOf(source.translateBatch(KURMANJI, List.of("OK")))).isInstanceOf(MtError.Unsupported.class);
    }

    @Test
    void translateBatchMapsA200ResponseThatIsntJsonToUnsupported() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));
        assertThat(errorOf(source.translateBatch(KURMANJI, List.of("OK")))).isInstanceOf(MtError.Unsupported.class);
    }
}

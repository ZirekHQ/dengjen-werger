package werger.adapters.googletranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class GoogleTranslateClientTest {
    private static final String ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();

    private static ResponseCreator ok(String json) {
        return withSuccess(json, MediaType.APPLICATION_JSON);
    }

    private static final String ONE_X = """
            {"data":{"translations":[{"translatedText":"x"}]}}""";

    // Answers "x" only for a request whose body names "a"; everything else gets `other`.
    private static ResponseCreator onlyA(ResponseCreator other) {
        return request -> ((MockClientHttpRequest) request).getBodyAsString().contains("\"a\"")
                ? ok(ONE_X).createResponse(request)
                : other.createResponse(request);
    }

    @Test
    void translatePairsEachSourceTextWithItsTranslation() {
        server.expect(requestTo(ENDPOINT)).andRespond(ok("""
                {"data":{"translations":[{"translatedText":"Temam"},{"translatedText":"Na"}]}}"""));
        GoogleTranslateClient client = new GoogleTranslateClient(builder.build(), "test-key");
        assertThat(client.translate(List.of("OK", "No"), "ku")).isEqualTo(Map.of("OK", "Temam", "No", "Na"));
    }

    @Test
    void translateSplitsRequestsLargerThanChunkSizeIntoMultipleCalls() {
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(ok(ONE_X));
        new GoogleTranslateClient(builder.build(), "test-key", 1, 100_000).translate(List.of("a", "b", "c"), "ku");
        server.verify();
    }

    @Test
    void translateSendsTheApiKeyAsAHeaderNeverInTheRequestUri() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(header("X-goog-api-key", "super-secret-key"))
                .andRespond(ok("""
                        {"data":{"translations":[{"translatedText":"Temam"}]}}"""));
        new GoogleTranslateClient(builder.build(), "super-secret-key").translate(List.of("OK"), "ku");
        server.verify();
    }

    @Test
    void aChunkWithFewerTranslationsThanRequestedTextsIsTreatedAsFailedOtherChunksUnaffected() {
        server.expect(manyTimes(), requestTo(ENDPOINT)).andRespond(onlyA(ok("""
                {"data":{"translations":[]}}""")));
        GoogleTranslateClient client = new GoogleTranslateClient(builder.build(), "test-key", 1, 100_000);
        assertThat(client.translate(List.of("a", "b"), "ku")).isEqualTo(Map.of("a", "x"));
    }

    @Test
    void aFailedChunkDoesntDiscardAnotherChunksAlreadySucceededTranslations() {
        server.expect(manyTimes(), requestTo(ENDPOINT)).andRespond(onlyA(withServerError()));
        GoogleTranslateClient client = new GoogleTranslateClient(builder.build(), "test-key", 1, 100_000);
        assertThat(client.translate(List.of("a", "b"), "ku")).isEqualTo(Map.of("a", "x"));
    }

    @Test
    void translateSplitsAChunkThatWouldExceedMaxRequestBytesEvenUnderChunkSize() {
        // One serialized request for "aaaa" is 44 bytes, for both texts together 51 — a 45-byte cap fits one but not
        // both, forcing a split by size alone even though both texts fit well under chunkSize.
        server.expect(times(2), requestTo(ENDPOINT)).andRespond(ok(ONE_X));
        new GoogleTranslateClient(builder.build(), "test-key", 100, 45).translate(List.of("aaaa", "bbbb"), "ku");
        server.verify();
    }

    @Test
    void translateExcludesATextWhoseOwnRequestWouldExceedMaxRequestBytesWithoutCallingGoogle() {
        // No single text's request can fit under a 5-byte cap, so nothing should ever be sent.
        server.expect(never(), requestTo(ENDPOINT));
        GoogleTranslateClient client = new GoogleTranslateClient(builder.build(), "test-key", 100, 5);
        assertThat(client.translate(List.of("a", "b"), "ku")).isEmpty();
        server.verify();
    }

    @Test
    void theConstructorRejectsLimitsOutsideWhatGoogleAccepts() {
        RestClient http = builder.build();
        assertThatIllegalArgumentException().isThrownBy(() -> new GoogleTranslateClient(http, "k", 0, 100_000));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoogleTranslateClient(http, "k", 129, 100_000));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoogleTranslateClient(http, "k", 100, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoogleTranslateClient(http, "k", 100, 100_001));
    }

    @Test
    void aResponseItemWithoutTranslatedTextIsAnUnexpectedShape() {
        server.expect(requestTo(ENDPOINT)).andRespond(ok("""
                {"data":{"translations":[{}]}}"""));
        GoogleTranslateClient client = new GoogleTranslateClient(builder.build(), "test-key");
        assertThatExceptionOfType(UnexpectedResponseShapeException.class)
                .isThrownBy(() -> client.translate(List.of("a"), "ku"));
    }

    @Test
    void translateRaisesWhenEveryChunkFails() {
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());
        GoogleTranslateClient client = new GoogleTranslateClient(builder.build(), "test-key");
        assertThatExceptionOfType(RestClientResponseException.class)
                .isThrownBy(() -> client.translate(List.of("a"), "ku"));
    }
}

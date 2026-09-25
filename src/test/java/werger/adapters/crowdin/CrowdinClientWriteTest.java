package werger.adapters.crowdin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class CrowdinClientWriteTest {
    private static final String PROJECT = "https://api.crowdin.com/api/v2/projects/780748";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final CrowdinClient crowdin = new CrowdinClient(builder.build(), "test-token", 780748L);

    private static org.springframework.test.web.client.ResponseCreator created(String json) {
        return withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @Test
    void createTranslationPostsTheStringLanguageAndTextAndDecodesTheCreatedId() {
        server.expect(requestTo(PROJECT + "/translations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"stringId":661,"languageId":"kmr","text":"Temam"}""", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(created("""
                        {"data":{"id":42}}"""));
        assertThat(crowdin.createTranslation("kmr", 661L, "Temam").id()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void createTranslationSendsTheBearerToken() {
        server.expect(requestTo(PROJECT + "/translations"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(created("""
                        {"data":{"id":42}}"""));
        assertThat(crowdin.createTranslation("kmr", 661L, "Temam").id()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void createTranslationFailsWhenCrowdinRejectsTheRequest() {
        server.expect(requestTo(PROJECT + "/translations"))
                .andRespond(withBadRequest().body("""
                        {"error":"nope"}"""));
        assertThatExceptionOfType(RestClientResponseException.class)
                .isThrownBy(() -> crowdin.createTranslation("kmr", 661L, "Temam"));
    }

    @Test
    void approveTranslationPostsTheTranslationIdToApprovals() {
        server.expect(requestTo(PROJECT + "/approvals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"translationId":42}""", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(created("""
                        {"data":{"id":7}}"""));
        crowdin.approveTranslation(42L);
        server.verify();
    }

    @Test
    void approveTranslationFailsWhenCrowdinRejectsTheApproval() {
        server.expect(requestTo(PROJECT + "/approvals")).andRespond(withResourceNotFound());
        assertThatExceptionOfType(RestClientResponseException.class).isThrownBy(() -> crowdin.approveTranslation(42L));
    }
}

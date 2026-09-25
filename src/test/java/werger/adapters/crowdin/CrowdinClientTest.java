package werger.adapters.crowdin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CrowdinClientTest {
    private static final String PROJECT = "https://api.crowdin.com/api/v2/projects/780748";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

    private CrowdinClient crowdin(long pageSize) {
        return new CrowdinClient(builder.build(), "test-token", 780748L, pageSize);
    }

    private void respond(String pathPrefix, String json) {
        server.expect(requestTo(Matchers.startsWith(PROJECT + pathPrefix)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void sourceStringsDecodesCrowdinsPaginatedResponseShape() {
        respond("/files/661/strings", """
                {"data":[{"data":{"id":661,"text":"OK","identifier":"addon.OK"}}]}""");
        assertThat(crowdin(500).sourceStrings(661L))
                .extracting(CrowdinSourceString::text)
                .containsExactly("OK");
        server.verify();
    }

    @Test
    void sourceStringsFollowsCrowdinsPaginationUntilAPageComesBackShort() {
        server.expect(requestTo(Matchers.startsWith(PROJECT + "/files/661/strings")))
                .andExpect(queryParam("offset", "0"))
                .andRespond(withSuccess("""
                        {"data":[{"data":{"id":1,"text":"One","identifier":"a"}},\
                        {"data":{"id":2,"text":"Two","identifier":"b"}}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(PROJECT + "/files/661/strings")))
                .andExpect(queryParam("offset", "2"))
                .andRespond(withSuccess("""
                        {"data":[{"data":{"id":3,"text":"Three","identifier":"c"}}]}""", MediaType.APPLICATION_JSON));
        assertThat(crowdin(2).sourceStrings(661L))
                .extracting(CrowdinSourceString::text)
                .containsExactly("One", "Two", "Three");
        server.verify();
    }

    @Test
    void approvedTranslationsExcludesATranslationThatHasNoMatchingApproval() {
        respond("/languages/kmr/translations", """
                {"data":[{"data":{"id":42,"stringId":661,"text":"Temam"}}]}""");
        respond("/approvals", """
                {"data":[]}""");
        assertThat(crowdin(500).approvedTranslations("kmr", 661L)).isEmpty();
    }

    @Test
    void approvedTranslationsKeepsOnlyTranslationsThatAppearInCrowdinsApprovalsList() {
        respond("/languages/kmr/translations", """
                {"data":[{"data":{"id":42,"stringId":661,"text":"Temam"}},\
                {"data":{"id":43,"stringId":662,"text":"Na"}}]}""");
        respond("/approvals", """
                {"data":[{"data":{"translationId":42}}]}""");
        List<CrowdinTranslation> approved = crowdin(500).approvedTranslations("kmr", 661L);
        assertThat(approved).extracting(CrowdinTranslation::text).containsExactly("Temam");
    }
}

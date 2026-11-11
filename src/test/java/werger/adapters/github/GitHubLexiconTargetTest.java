package werger.adapters.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import werger.ports.Result;
import werger.ports.SourceError;

class GitHubLexiconTargetTest {
    private static final String REPO = "https://api.github.com/repos/ZirekHQ/dengjen/";
    private static final String FILE = REPO + "contents/crates/text/kurmanji-phonemizer/lexicon/kmr.tsv";
    private static final String BRANCH = "werger/lexicon-kmr";
    private static final String LEXICON = "written\tsay\tnote\nAB\tYekîtiya Ewropayê\n";
    private static final String PULL = """
            [{"number":7,"html_url":"https://github.com/ZirekHQ/dengjen/pull/7","state":"open"}]""";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final GitHubLexiconTarget target =
            new GitHubLexiconTarget(new GitHubClient(builder.build(), "test-token", "ZirekHQ", "dengjen"), "main");

    private void expectOpenPulls(String json) {
        server.expect(requestTo(REPO + "pulls?head=ZirekHQ:" + BRANCH + "&state=open"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectRawFile(String ref, String content) {
        server.expect(requestTo(FILE + "?ref=" + ref))
                .andExpect(header("Accept", "application/vnd.github.raw+json"))
                .andRespond(withSuccess(content.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN));
    }

    private void expectCommitOntoAFreshBranch(String content) {
        server.expect(requestTo(REPO + "git/ref/heads/main")).andRespond(withSuccess("""
                        {"object":{"sha":"main-sha"}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ref").value("refs/heads/" + BRANCH))
                .andExpect(jsonPath("$.sha").value("main-sha"))
                .andRespond(withStatus(HttpStatus.CREATED));
        expectPutFile(content);
    }

    private void expectPutFile(String content) {
        server.expect(requestTo(FILE + "?ref=" + BRANCH))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andRespond(withSuccess("""
                        {"sha":"file-sha"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(FILE))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.branch").value(BRANCH))
                .andExpect(jsonPath("$.sha").value("file-sha"))
                .andExpect(jsonPath("$.content")
                        .value(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))))
                .andRespond(withSuccess());
    }

    @Test
    void currentReadsTheLexiconFromTheBaseBranchAsUtf8() {
        server.expect(requestTo(FILE + "?ref=main"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess(LEXICON.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN));

        assertThat(target.current("kmr")).isEqualTo(Result.ok(LEXICON));
        server.verify();
    }

    @Test
    void withNoOpenPullRequestItCommitsOntoTheBranchAndOpensOne() {
        String content = LEXICON + "NY\tNeteweyên Yekbûyî\n";
        expectOpenPulls("[]");
        expectCommitOntoAFreshBranch(content);
        server.expect(requestTo(REPO + "pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.head").value(BRANCH))
                .andExpect(jsonPath("$.base").value("main"))
                .andExpect(jsonPath("$.body").value("the rows"))
                .andRespond(withSuccess("""
                        {"number":8,"html_url":"https://github.com/ZirekHQ/dengjen/pull/8"}""", MediaType.APPLICATION_JSON));

        assertThat(target.propose("kmr", content, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/8")));
        server.verify();
    }

    @Test
    void aBranchLeftFromAnEarlierPullRequestIsResetToTheBase() {
        String content = LEXICON + "NY\tNeteweyên Yekbûyî\n";
        expectOpenPulls("[]");
        server.expect(requestTo(REPO + "git/ref/heads/main")).andRespond(withSuccess("""
                        {"object":{"sha":"main-sha"}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
        server.expect(requestTo(REPO + "git/refs/heads/" + BRANCH))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.sha").value("main-sha"))
                .andExpect(jsonPath("$.force").value(true))
                .andRespond(withSuccess());
        expectPutFile(content);
        server.expect(requestTo(REPO + "pulls"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"number":8,"html_url":"https://github.com/ZirekHQ/dengjen/pull/8"}""", MediaType.APPLICATION_JSON));

        assertThat(target.propose("kmr", content, "the rows")).isInstanceOf(Result.Ok.class);
        server.verify();
    }

    @Test
    void anOpenPullRequestWithTheSameFileOnlyGetsTheNewDescription() {
        expectOpenPulls(PULL);
        expectRawFile(BRANCH, LEXICON);
        server.expect(requestTo(REPO + "pulls/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.body").value("the rows"))
                .andRespond(withSuccess());

        assertThat(target.propose("kmr", LEXICON, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/7")));
        server.verify();
    }

    @Test
    void anOpenPullRequestWithAnOlderFileIsUpdatedInPlace() {
        String content = LEXICON + "NY\tNeteweyên Yekbûyî\n";
        expectOpenPulls(PULL);
        expectRawFile(BRANCH, LEXICON);
        expectCommitOntoAFreshBranch(content);
        server.expect(requestTo(REPO + "pulls/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        assertThat(target.propose("kmr", content, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/7")));
        server.verify();
    }

    @Test
    void aRefusedTokenIsRejected() {
        server.expect(requestTo(FILE + "?ref=main")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
    }

    @Test
    void aLostCommitRaceAndAnOutageAreTransient() {
        expectOpenPulls("[]");
        server.expect(requestTo(REPO + "git/ref/heads/main")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(target.propose("kmr", LEXICON, "the rows"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));

        server.reset();
        server.expect(requestTo(FILE + "?ref=main")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));
    }

    @Test
    void anotherFailureOnTheBranchIsNotMistakenForAnExistingBranch() {
        expectOpenPulls("[]");
        server.expect(requestTo(REPO + "git/ref/heads/main")).andRespond(withSuccess("""
                        {"object":{"sha":"main-sha"}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "git/refs")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(target.propose("kmr", LEXICON, "the rows"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
        server.verify();
    }
}

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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import werger.ports.LexiconTarget;
import werger.ports.Result;
import werger.ports.SourceError;

/** {@link GitHubLexiconTarget} against a mocked GitHub REST API. */
class GitHubLexiconTargetTest {
    private static final String REPO = "https://api.github.com/repos/ZirekHQ/dengjen/";
    private static final String FILE = REPO + "contents/crates/text/kurmanji-phonemizer/lexicon/kmr.tsv";
    private static final String BRANCH = "werger/lexicon-kmr";
    private static final String LEXICON = "written\tsay\tnote\nAB\tYekîtiya Ewropayê\n";
    private static final String PULL = """
            [{"number":7,"html_url":"https://github.com/ZirekHQ/dengjen/pull/7","state":"open","head":{"sha":"old-proposal"}}]""";

    private static final LexiconTarget.Snapshot SNAPSHOT = new LexiconTarget.Snapshot(LEXICON, "main-sha");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final GitHubLexiconTarget target =
            new GitHubLexiconTarget(new GitHubClient(builder.build(), "test-token", "ZirekHQ", "dengjen"), "main");

    /** GitHub lists {@code json} as the open pull requests from the proposal branch. */
    private void expectOpenPulls(String json) {
        server.expect(requestTo(REPO + "pulls?head=ZirekHQ:" + BRANCH + "&state=open"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    /** The open pull request's head commit sits on {@code parent}. */
    private void expectProposalParent(String parent) {
        server.expect(requestTo(REPO + "git/commits/old-proposal"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"sha":"old-proposal","tree":{"sha":"old-tree"},"parents":[{"sha":"%s"}]}""".formatted(parent), MediaType.APPLICATION_JSON));
    }

    /** GitHub serves {@code content} as the lexicon at {@code ref}. */
    private void expectRawFile(String ref, String content) {
        server.expect(requestTo(FILE + "?ref=" + ref))
                .andExpect(header("Accept", "application/vnd.github.raw+json"))
                .andRespond(withSuccess(content.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN));
    }

    /** The proposal is committed with the snapshot's revision as its only parent. */
    private void expectCommit(String content) {
        server.expect(requestTo(REPO + "git/commits/main-sha"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"sha":"main-sha","tree":{"sha":"main-tree"}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "git/trees"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.base_tree").value("main-tree"))
                .andExpect(jsonPath("$.tree[0].path").value("crates/text/kurmanji-phonemizer/lexicon/kmr.tsv"))
                .andExpect(jsonPath("$.tree[0].mode").value("100644"))
                .andExpect(jsonPath("$.tree[0].content").value(content))
                .andRespond(withSuccess("""
                        {"sha":"new-tree"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPO + "git/commits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.tree").value("new-tree"))
                .andExpect(jsonPath("$.parents[0]").value("main-sha"))
                .andRespond(withSuccess("""
                        {"sha":"proposal-sha"}""", MediaType.APPLICATION_JSON));
    }

    /** The proposal is committed and a new branch is created at it. */
    private void expectCommitOntoAFreshBranch(String content) {
        expectCommit(content);
        server.expect(requestTo(REPO + "git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ref").value("refs/heads/" + BRANCH))
                .andExpect(jsonPath("$.sha").value("proposal-sha"))
                .andRespond(withStatus(HttpStatus.CREATED));
    }

    /** The proposal is committed and the existing branch is forced to it. */
    private void expectCommitOntoTheExistingBranch(String content) {
        expectCommit(content);
        server.expect(requestTo(REPO + "git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
        server.expect(requestTo(REPO + "git/refs/heads/" + BRANCH))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.sha").value("proposal-sha"))
                .andExpect(jsonPath("$.force").value(true))
                .andRespond(withSuccess());
    }

    @Test
    void currentReadsTheLexiconAtTheBaseBranchsCommitAsUtf8() {
        server.expect(requestTo(REPO + "git/ref/heads/main"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("""
                        {"object":{"sha":"main-sha"}}""", MediaType.APPLICATION_JSON));
        expectRawFile("main-sha", LEXICON);

        assertThat(target.current("kmr")).isEqualTo(Result.ok(SNAPSHOT));
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

        assertThat(target.propose("kmr", SNAPSHOT, content, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/8")));
        server.verify();
    }

    @Test
    void aBranchLeftFromAnEarlierPullRequestIsMovedToTheNewCommit() {
        String content = LEXICON + "NY\tNeteweyên Yekbûyî\n";
        expectOpenPulls("[]");
        expectCommitOntoTheExistingBranch(content);
        server.expect(requestTo(REPO + "pulls"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"number":8,"html_url":"https://github.com/ZirekHQ/dengjen/pull/8"}""", MediaType.APPLICATION_JSON));

        assertThat(target.propose("kmr", SNAPSHOT, content, "the rows")).isInstanceOf(Result.Ok.class);
        server.verify();
    }

    @Test
    void anOpenPullRequestWithTheSameFileOnlyGetsTheNewDescriptionAndIsKeptOnTheBase() {
        expectOpenPulls(PULL);
        expectProposalParent("main-sha");
        expectRawFile(BRANCH, LEXICON);
        server.expect(requestTo(REPO + "pulls/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.body").value("the rows"))
                // Someone may have retargeted it by hand; pointing it back keeps one pull request per language.
                .andExpect(jsonPath("$.base").value("main"))
                .andRespond(withSuccess());

        assertThat(target.propose("kmr", SNAPSHOT, LEXICON, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/7")));
        server.verify();
    }

    @Test
    void anOpenPullRequestWithAnOlderFileIsUpdatedInPlace() {
        String content = LEXICON + "NY\tNeteweyên Yekbûyî\n";
        expectOpenPulls(PULL);
        expectProposalParent("main-sha");
        expectRawFile(BRANCH, LEXICON);
        // Straight from the old proposal to the new one: the branch never matches the base, so the pull request stays
        // open.
        expectCommitOntoTheExistingBranch(content);
        server.expect(requestTo(REPO + "pulls/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        assertThat(target.propose("kmr", SNAPSHOT, content, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/7")));
        server.verify();
    }

    @Test
    void anOpenPullRequestOnAnOlderBaseIsRebuiltEvenWithTheSameFile() {
        expectOpenPulls(PULL);
        expectProposalParent("older-main-sha");
        // Merged from the older parent, a row the base changed since could survive although this file replaces it.
        expectCommitOntoTheExistingBranch(LEXICON);
        server.expect(requestTo(REPO + "pulls/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        assertThat(target.propose("kmr", SNAPSHOT, LEXICON, "the rows"))
                .isEqualTo(Result.ok(URI.create("https://github.com/ZirekHQ/dengjen/pull/7")));
        server.verify();
    }

    @Test
    void aRefusedTokenIsRejected() {
        server.expect(requestTo(REPO + "git/ref/heads/main")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
    }

    @Test
    void aLostCommitRaceAndAnOutageAreTransient() {
        expectOpenPulls("[]");
        server.expect(requestTo(REPO + "git/commits/main-sha")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(target.propose("kmr", SNAPSHOT, LEXICON, "the rows"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));

        server.reset();
        server.expect(requestTo(REPO + "git/ref/heads/main")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));
    }

    @Test
    void anotherFailureOnTheBranchIsNotMistakenForAnExistingBranch() {
        expectOpenPulls("[]");
        expectCommit(LEXICON);
        server.expect(requestTo(REPO + "git/refs")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(target.propose("kmr", SNAPSHOT, LEXICON, "the rows"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
        server.verify();
    }

    @Test
    void aSpentRateLimitIsTransientThoughGitHubAnswers403() {
        HttpHeaders spent = new HttpHeaders();
        spent.set("X-RateLimit-Remaining", "0");
        server.expect(requestTo(REPO + "git/ref/heads/main"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(spent));
        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));

        server.reset();
        HttpHeaders secondary = new HttpHeaders();
        secondary.set(HttpHeaders.RETRY_AFTER, "60");
        server.expect(requestTo(REPO + "git/ref/heads/main"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(secondary));
        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));

        server.reset();
        server.expect(requestTo(REPO + "git/ref/heads/main"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"message":"You have exceeded a secondary rate limit. Please wait a few minutes."}"""));
        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Transient.class));
    }

    @Test
    void a403WithRateLimitLeftIsAPermissionRefusal() {
        HttpHeaders left = new HttpHeaders();
        left.set("X-RateLimit-Remaining", "4999");
        server.expect(requestTo(REPO + "git/ref/heads/main"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(left));

        assertThat(target.current("kmr"))
                .isInstanceOfSatisfying(
                        Result.Err.class, err -> assertThat(err.error()).isInstanceOf(SourceError.Rejected.class));
    }
}

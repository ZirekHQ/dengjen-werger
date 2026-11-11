package werger.adapters.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** The few calls of GitHub's REST API that one repository's lexicon pull request needs. */
public class GitHubClient {
    private static final String API = "https://api.github.com/repos/";
    private static final MediaType JSON = MediaType.parseMediaType("application/vnd.github+json");
    private static final MediaType RAW = MediaType.parseMediaType("application/vnd.github.raw+json");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ref(GitObject object) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitObject(String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Commit(GitObject tree) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pull(long number, @JsonProperty("html_url") URI htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Created(String sha) {}

    private final RestClient http;
    private final String token;
    private final String owner;
    private final String repo;

    /** A client for {@code owner/repo} that signs every request with {@code token}. */
    public GitHubClient(RestClient http, String token, String owner, String repo) {
        this.http = http;
        this.token = token;
        this.owner = owner;
        this.repo = repo;
    }

    private UriComponentsBuilder at(String path) {
        return UriComponentsBuilder.fromUriString(API + owner + "/" + repo + "/" + path);
    }

    private void headers(HttpHeaders headers, MediaType accept) {
        headers.setBearerAuth(token);
        headers.setAccept(List.of(accept));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
    }

    /** Decoded as UTF-8 whatever the response says: a String body without a charset would be read as ISO-8859-1. */
    public String rawFile(String path, String ref) {
        byte[] body = http.get()
                .uri(at("contents/" + path).queryParam("ref", ref).build().toUri())
                .headers(h -> headers(h, RAW))
                .retrieve()
                .body(byte[].class);
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    /** The commit {@code branch} points at. */
    public String branchSha(String branch) {
        Ref ref = http.get()
                .uri(at("git/ref/heads/" + branch).build().toUri())
                .headers(h -> headers(h, JSON))
                .retrieve()
                .body(Ref.class);
        return Objects.requireNonNull(ref).object().sha();
    }

    /** Points {@code branch} at {@code sha}, creating it if it is missing and dropping any commits only it had. */
    public void pointBranch(String branch, String sha) {
        try {
            send(HttpMethod.POST, at("git/refs").build().toUri(), Map.of("ref", "refs/heads/" + branch, "sha", sha));
        } catch (HttpClientErrorException e) {
            // GitHub answers 422 when the branch already exists; anything else is a real failure.
            if (e.getStatusCode().value() != 422) {
                throw e;
            }
            send(HttpMethod.PATCH, at("git/refs/heads/" + branch).build().toUri(), Map.of("sha", sha, "force", true));
        }
    }

    /**
     * Creates a commit whose only parent is {@code parent} and whose tree is the parent's with {@code path} set to
     * {@code content}, and returns its SHA. No branch moves until {@link #pointBranch} points one at it.
     */
    public String commitFile(String parent, String path, String content, String message) {
        Commit commit = http.get()
                .uri(at("git/commits/" + parent).build().toUri())
                .headers(h -> headers(h, JSON))
                .retrieve()
                .body(Commit.class);
        String tree = create(
                "git/trees",
                Map.of(
                        "base_tree",
                        Objects.requireNonNull(commit).tree().sha(),
                        "tree",
                        List.of(Map.of("path", path, "mode", "100644", "type", "blob", "content", content))));
        return create("git/commits", Map.of("message", message, "tree", tree, "parents", List.of(parent)));
    }

    /** The open pull request from {@code branch} in this repository, if there is one. */
    public Optional<Pull> openPull(String branch) {
        List<Pull> pulls = http.get()
                .uri(at("pulls")
                        .queryParam("head", owner + ":" + branch)
                        .queryParam("state", "open")
                        .build()
                        .toUri())
                .headers(h -> headers(h, JSON))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Pull>>() {});
        return pulls == null ? Optional.empty() : pulls.stream().findFirst();
    }

    /** Opens a pull request from {@code branch} into {@code base}. */
    public Pull createPull(String title, String branch, String base, String body) {
        Pull pull = http.post()
                .uri(at("pulls").build().toUri())
                .headers(h -> headers(h, JSON))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("title", title, "head", branch, "base", base, "body", body))
                .retrieve()
                .body(Pull.class);
        return Objects.requireNonNull(pull);
    }

    /** Replaces the description of pull request {@code number}. */
    public void updatePullBody(long number, String body) {
        send(HttpMethod.PATCH, at("pulls/" + number).build().toUri(), Map.of("body", body));
    }

    private String create(String path, Map<String, Object> body) {
        Created created = http.post()
                .uri(at(path).build().toUri())
                .headers(h -> headers(h, JSON))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Created.class);
        return Objects.requireNonNull(created).sha();
    }

    private void send(HttpMethod method, URI uri, Map<String, Object> body) {
        http.method(method)
                .uri(uri)
                .headers(h -> headers(h, JSON))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}

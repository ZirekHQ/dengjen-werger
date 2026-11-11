package werger.adapters.github;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import werger.ports.LexiconTarget;
import werger.ports.Result;
import werger.ports.SourceError;

/**
 * Keeps one pull request open on dengjen per language, from the branch {@code werger/lexicon-<language>}. Werger owns
 * that branch: each new proposal is one commit on top of the base revision it was built from, and the branch is moved
 * straight to it, so an edit pushed to the branch by hand is replaced by the next proposal that changes the rows.
 */
public class GitHubLexiconTarget implements LexiconTarget {
    // 409 is a commit that lost a race with another write to the branch; the next run starts again from the base.
    private static final Set<HttpStatusCode> RETRYABLE_4XX =
            Set.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.CONFLICT, HttpStatus.TOO_MANY_REQUESTS);

    private final GitHubClient github;
    private final String base;

    /** Proposes changes through {@code github} against the branch {@code base}. */
    public GitHubLexiconTarget(GitHubClient github, String base) {
        this.github = github;
        this.base = base;
    }

    static String path(String language) {
        return "crates/text/kurmanji-phonemizer/lexicon/" + language + ".tsv";
    }

    static String branch(String language) {
        return "werger/lexicon-" + language;
    }

    @Override
    public Result<SourceError, Snapshot> current(String language) {
        return attempt(() -> {
            // Read at a commit, not the branch name, so the content and the revision cannot come from different
            // commits.
            String revision = github.branchSha(base);
            return new Snapshot(github.rawFile(path(language), revision), revision);
        });
    }

    @Override
    public Result<SourceError, URI> propose(String language, Snapshot base, String content, String description) {
        return attempt(() -> proposeOrThrow(language, base, content, description));
    }

    private URI proposeOrThrow(String language, Snapshot snapshot, String content, String description) {
        String path = path(language);
        String branch = branch(language);
        Optional<GitHubClient.Pull> open = github.openPull(branch);
        // An unchanged file needs no commit, which would only start dengjen's CI again.
        if (open.isEmpty() || !github.rawFile(path, branch).equals(content)) {
            // Never point the branch at the base itself: GitHub closes an open pull request that has no commits left.
            github.pointBranch(branch, github.commitFile(snapshot.revision(), path, content, title(language)));
        }
        return open.map(pull -> {
                    github.updatePullBody(pull.number(), description);
                    return pull.htmlUrl();
                })
                .orElseGet(() -> github.createPull(title(language), branch, base, description)
                        .htmlUrl());
    }

    private static String title(String language) {
        return "feat(lexicon): add pronunciation fixes from native speakers (" + language + ")";
    }

    private static <A> Result<SourceError, A> attempt(Supplier<A> call) {
        try {
            return Result.ok(call.get());
        } catch (RestClientResponseException e) {
            return Result.err(toError(e));
        } catch (RestClientException e) {
            return Result.err(new SourceError.Transient(String.valueOf(e.getMessage())));
        }
    }

    // 4xx means GitHub refused this request (a bad token, a protected branch), except the codes that clear on retry.
    private static SourceError toError(RestClientResponseException e) {
        return e.getStatusCode().is4xxClientError() && !RETRYABLE_4XX.contains(e.getStatusCode()) && !rateLimited(e)
                ? new SourceError.Rejected("GitHub rejected the request: " + e.getStatusCode())
                : new SourceError.Transient("GitHub returned " + e.getStatusCode());
    }

    // GitHub answers a spent rate limit with 403 as well as 429, and marks it only in these headers.
    private static boolean rateLimited(RestClientResponseException e) {
        HttpHeaders headers = Optional.ofNullable(e.getResponseHeaders()).orElseGet(HttpHeaders::new);
        return "0".equals(headers.getFirst("X-RateLimit-Remaining")) || headers.containsHeader(HttpHeaders.RETRY_AFTER);
    }
}

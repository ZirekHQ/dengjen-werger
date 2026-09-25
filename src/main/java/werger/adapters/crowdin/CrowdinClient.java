package werger.adapters.crowdin;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads Crowdin's current translation state for one project/file: its source strings and the translations already
 * approved against them.
 */
public class CrowdinClient {
    private static final String BASE = "https://api.crowdin.com/api/v2/projects/";

    private final RestClient httpClient;
    private final String token;
    private final long projectId;
    private final long pageSize;

    public CrowdinClient(RestClient httpClient, String token, long projectId) {
        this(httpClient, token, projectId, 500L);
    }

    public CrowdinClient(RestClient httpClient, String token, long projectId, long pageSize) {
        this.httpClient = httpClient;
        this.token = token;
        this.projectId = projectId;
        this.pageSize = pageSize;
    }

    /**
     * Follows Crowdin's limit/offset pagination until a page comes back shorter than {@code pageSize}, since Crowdin's
     * list endpoints cap a single response (default limit 25) well below what a real file's string count can reach.
     */
    private <A> List<A> paginate(LongFunction<URI> uriAt, ParameterizedTypeReference<CrowdinEnvelope<A>> type) {
        List<A> all = new ArrayList<>();
        long offset = 0L;
        while (true) {
            CrowdinEnvelope<A> envelope = httpClient
                    .get()
                    .uri(uriAt.apply(offset))
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(type);
            List<A> page = envelope == null || envelope.data() == null
                    ? List.of()
                    : envelope.data().stream()
                            .map(CrowdinEnvelope.Wrapped::data)
                            .toList();
            all.addAll(page);
            if (page.size() < pageSize) {
                return all;
            }
            offset += pageSize;
        }
    }

    private UriComponentsBuilder resource(String path, long offset) {
        return UriComponentsBuilder.fromUriString(BASE + projectId + "/" + path)
                .queryParam("limit", pageSize)
                .queryParam("offset", offset);
    }

    public List<CrowdinSourceString> sourceStrings(long fileId) {
        return paginate(
                offset ->
                        resource("files/" + fileId + "/strings", offset).build().toUri(),
                new ParameterizedTypeReference<>() {});
    }

    public List<CrowdinTranslation> approvedTranslations(String languageId, long fileId) {
        List<CrowdinTranslation> translations = paginate(
                offset -> resource("languages/" + languageId + "/translations", offset)
                        .queryParam("fileId", fileId)
                        .build()
                        .toUri(),
                new ParameterizedTypeReference<CrowdinEnvelope<CrowdinTranslation>>() {});
        // /translations returns every submitted translation regardless of
        // approval state; Crowdin has no approval-status filter on that
        // endpoint, so the approved subset has to come from /approvals instead.
        Set<Long> approvedIds = paginate(
                        offset -> resource("approvals", offset)
                                .queryParam("fileId", fileId)
                                .queryParam("languageId", languageId)
                                .build()
                                .toUri(),
                        new ParameterizedTypeReference<CrowdinEnvelope<CrowdinApproval>>() {})
                .stream()
                .map(CrowdinApproval::translationId)
                .collect(Collectors.toSet());
        return translations.stream().filter(t -> approvedIds.contains(t.id())).toList();
    }

    public CrowdinCreatedTranslation createTranslation(String languageId, long stringId, String text) {
        CrowdinEnvelope.Wrapped<CrowdinCreatedTranslation> created = post(
                        "translations", Map.of("stringId", stringId, "languageId", languageId, "text", text))
                .body(new ParameterizedTypeReference<>() {});
        if (created == null || created.data() == null) {
            throw new IllegalStateException("Crowdin returned no created translation");
        }
        return created.data();
    }

    public void approveTranslation(long translationId) {
        post("approvals", Map.of("translationId", translationId)).toBodilessEntity();
    }

    private RestClient.ResponseSpec post(String resource, Map<String, Object> body) {
        return httpClient
                .post()
                .uri(URI.create(BASE + projectId + "/" + resource))
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve();
    }
}

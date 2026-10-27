package werger.adapters.crowdin;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;
import werger.domain.Language;
import werger.domain.TmSegment;
import werger.domain.WorkItem;
import werger.domain.WorkItemStatus;
import werger.ports.Result;
import werger.ports.SourceError;
import werger.ports.TranslationSource;

public class CrowdinSource implements TranslationSource {
    private static final String ADAPTER = "crowdin";
    private static final Set<HttpStatusCode> RETRYABLE_4XX =
            Set.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS);

    private final CrowdinClient client;
    private final long fileId;

    public CrowdinSource(CrowdinClient client, long fileId) {
        this.client = client;
        this.fileId = fileId;
    }

    @Override
    public List<WorkItem> fetchWorkItems(Language language) {
        List<CrowdinTranslation> translations = client.getTranslations(language.code(), fileId);
        Set<Long> approvedTranslationIds = client.approvedTranslationIds(language.code(), fileId);
        Set<Long> approved = translations.stream()
                .filter(t -> approvedTranslationIds.contains(t.translationId()))
                .map(CrowdinTranslation::stringId)
                .collect(Collectors.toSet());
        Map<Long, String> draftByStringId = latestDrafts(translations);
        return client.sourceStrings(fileId).stream()
                .filter(source -> !approved.contains(source.id()))
                .map(source -> toWorkItem(source, language, Optional.ofNullable(draftByStringId.get(source.id()))))
                .toList();
    }

    @Override
    public List<TmSegment> fetchMemory(Language language) {
        Map<Long, String> sourceTextById = client.sourceStrings(fileId).stream()
                .collect(Collectors.toMap(CrowdinSourceString::id, CrowdinSourceString::text));
        return client.approvedTranslations(language.code(), fileId).stream()
                .flatMap(t -> toSegment(t, sourceTextById, language).stream())
                .toList();
    }

    @Override
    public Result<SourceError, Void> submit(WorkItem item, String translation) {
        return parseStringId(item.externalId()).fold(Result::err, stringId -> create(item, stringId, translation));
    }

    private static Result<SourceError, Long> parseStringId(String externalId) {
        try {
            return Result.ok(Long.parseLong(externalId));
        } catch (NumberFormatException _) {
            return Result.err(new SourceError.Rejected("Crowdin string id is not numeric: " + externalId));
        }
    }

    private Result<SourceError, Void> create(WorkItem item, long stringId, String translation) {
        try {
            client.createTranslation(item.language(), stringId, translation);
            return Result.ok(null);
        } catch (RestClientResponseException e) {
            return Result.err(toError(e));
        } catch (RuntimeException e) {
            return Result.err(new SourceError.Transient(String.valueOf(e.getMessage())));
        }
    }

    // A string can hold several submissions; the highest translationId is the most recent one.
    private static Map<Long, String> latestDrafts(List<CrowdinTranslation> translations) {
        return translations.stream()
                .collect(Collectors.groupingBy(
                        CrowdinTranslation::stringId,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparingLong(CrowdinTranslation::translationId)),
                                best -> best.orElseThrow().text())));
    }

    private static WorkItem toWorkItem(CrowdinSourceString source, Language language, Optional<String> draft) {
        return new WorkItem(
                UUID.randomUUID(),
                ADAPTER,
                String.valueOf(source.id()),
                language.code(),
                source.text(),
                draft,
                new WorkItemStatus.Available());
    }

    private static Optional<TmSegment> toSegment(
            CrowdinTranslation translation, Map<Long, String> sourceTextById, Language language) {
        return Optional.ofNullable(sourceTextById.get(translation.stringId()))
                .map(sourceText ->
                        new TmSegment(language.code(), sourceText, translation.text(), ADAPTER, Instant.now()));
    }

    // 4xx means Crowdin refused this request, except 408 and 429, which clear on retry.
    private static SourceError toError(RestClientResponseException e) {
        return e.getStatusCode().is4xxClientError() && !RETRYABLE_4XX.contains(e.getStatusCode())
                ? new SourceError.Rejected("Crowdin rejected the request: " + e.getStatusCode())
                : new SourceError.Transient("Crowdin returned " + e.getStatusCode());
    }
}

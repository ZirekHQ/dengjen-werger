package werger.adapters.crowdin;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    private final CrowdinClient client;
    private final long fileId;

    public CrowdinSource(CrowdinClient client, long fileId) {
        this.client = client;
        this.fileId = fileId;
    }

    @Override
    public List<WorkItem> fetchWorkItems(Language language) {
        Set<Long> approved = approvedStringIds(language);
        Map<Long, String> draftByStringId = latestDrafts(language);
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
        try {
            client.createTranslation(item.language(), Long.parseLong(item.externalId()), translation);
            return Result.ok(null);
        } catch (RestClientResponseException e) {
            return Result.err(toError(e));
        } catch (RuntimeException e) {
            return Result.err(new SourceError.Transient(String.valueOf(e.getMessage())));
        }
    }

    private Set<Long> approvedStringIds(Language language) {
        return client.approvedTranslations(language.code(), fileId).stream()
                .map(CrowdinTranslation::stringId)
                .collect(Collectors.toSet());
    }

    // A string can hold several submissions; the highest translationId is the most recent one.
    private Map<Long, String> latestDrafts(Language language) {
        return client.getTranslations(language.code(), fileId).stream()
                .collect(Collectors.toMap(
                        CrowdinTranslation::stringId,
                        Function.identity(),
                        BinaryOperator.maxBy(Comparator.comparingLong(CrowdinTranslation::translationId))))
                .values()
                .stream()
                .collect(Collectors.toMap(CrowdinTranslation::stringId, CrowdinTranslation::text));
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

    // 4xx means Crowdin refused this request, so retrying the same input cannot succeed.
    private static SourceError toError(RestClientResponseException e) {
        return e.getStatusCode().is4xxClientError()
                ? new SourceError.Rejected("Crowdin rejected the request: " + e.getStatusCode())
                : new SourceError.Transient("Crowdin returned " + e.getStatusCode());
    }
}

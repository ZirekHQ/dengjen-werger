package werger.domain;

import java.util.Optional;
import java.util.UUID;

public record WorkItem(
        UUID id,
        String sourceAdapter,
        String externalId,
        String language,
        String sourceText,
        Optional<String> targetTextDraft,
        WorkItemStatus status) {}

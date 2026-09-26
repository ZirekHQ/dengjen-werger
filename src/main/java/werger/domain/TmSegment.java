package werger.domain;

import java.time.Instant;

public record TmSegment(
        String language, String sourceText, String targetText, String sourceAdapter, Instant importedAt) {}

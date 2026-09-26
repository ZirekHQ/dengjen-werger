package werger.domain;

import java.time.Instant;
import java.util.UUID;

public record Submission(UUID id, UUID workItem, UUID submitter, String proposedTranslation, Instant submittedAt) {}

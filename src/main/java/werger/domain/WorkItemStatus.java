package werger.domain;

import java.time.Instant;
import java.util.UUID;

public sealed interface WorkItemStatus {
    record Available() implements WorkItemStatus {}

    record InProgress(UUID userId, Instant expiresAt) implements WorkItemStatus {}

    record PendingReview(UUID submissionId) implements WorkItemStatus {}

    record RevisionPending(UUID submitterId, String reviewerComment, Instant expiresAt) implements WorkItemStatus {}

    record Approved() implements WorkItemStatus {}

    record UpstreamApprovalPending() implements WorkItemStatus {}

    record Synced() implements WorkItemStatus {}

    record Retired() implements WorkItemStatus {}
}

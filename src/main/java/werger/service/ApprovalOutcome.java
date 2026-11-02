package werger.service;

public sealed interface ApprovalOutcome {
    record Synced() implements ApprovalOutcome {}

    record AwaitingCountersign() implements ApprovalOutcome {}

    /** The push failed transiently, so the item is queued for retry. */
    record UpstreamPending() implements ApprovalOutcome {}

    /** Upstream refused the translation for good, so the item stays approved and a retry cannot help. */
    record UpstreamRejected(String message) implements ApprovalOutcome {}

    /** The item left {@code approved} before the result could be recorded, so nothing was written. */
    record Superseded() implements ApprovalOutcome {}
}

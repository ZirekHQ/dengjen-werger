package werger.service;

public sealed interface ApprovalOutcome {
    record Synced() implements ApprovalOutcome {}

    record AwaitingCountersign() implements ApprovalOutcome {}

    /** The push failed transiently, so the item is queued for retry. */
    record UpstreamPending() implements ApprovalOutcome {}

    /** Upstream refused the translation for good, so the item is parked in {@code upstream_rejected} and never retried. */
    record UpstreamRejected(String message) implements ApprovalOutcome {}

    /** The push threw before a result was recorded; the review stands and the sweep retries the push. */
    record PushDeferred() implements ApprovalOutcome {}

    /** Another push holds the item's claim, or the item is no longer owed one, so nothing was sent. */
    record PushInFlight() implements ApprovalOutcome {}

    /** The item left {@code approved} before the result could be recorded, so nothing was written. */
    record Superseded() implements ApprovalOutcome {}
}

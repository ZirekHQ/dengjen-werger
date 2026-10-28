package werger.service;

public sealed interface SubmitError {
    record NotLeaseHolder() implements SubmitError {}
}

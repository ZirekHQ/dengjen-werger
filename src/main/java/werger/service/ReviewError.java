package werger.service;

public sealed interface ReviewError {
    record OwnSubmission() implements ReviewError {}

    record AlreadyDecided() implements ReviewError {}
}

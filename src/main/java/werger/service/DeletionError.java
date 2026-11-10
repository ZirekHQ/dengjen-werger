package werger.service;

public sealed interface DeletionError {
    record UnknownUser() implements DeletionError {}
}

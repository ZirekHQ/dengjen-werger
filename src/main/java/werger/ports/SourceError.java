package werger.ports;

public sealed interface SourceError {
    record Transient(String message) implements SourceError {}

    record Rejected(String message) implements SourceError {}
}

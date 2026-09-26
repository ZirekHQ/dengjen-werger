package werger.ports;

public sealed interface MtError {
    record Transient(String message) implements MtError {}

    record Unsupported(String message) implements MtError {}
}

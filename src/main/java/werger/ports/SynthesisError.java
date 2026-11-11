package werger.ports;

/** Why the engine produced no speech. */
public sealed interface SynthesisError {
    /** The engine is not configured, not running, busy or too slow; trying later may work. */
    record Unavailable(String detail) implements SynthesisError {}

    /** The engine refused this text; trying again will not help. */
    record Rejected(String detail) implements SynthesisError {}
}

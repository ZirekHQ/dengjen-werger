package werger.ports;

/** The outcome of a port call whose failures are expected and must be handled, not thrown. */
public sealed interface Result<E, A> {
    record Ok<E, A>(A value) implements Result<E, A> {}

    record Err<E, A>(E error) implements Result<E, A> {}

    static <E, A> Result<E, A> ok(A value) {
        return new Ok<>(value);
    }

    static <E, A> Result<E, A> err(E error) {
        return new Err<>(error);
    }
}

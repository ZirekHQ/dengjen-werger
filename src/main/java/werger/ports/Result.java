package werger.ports;

import java.util.function.Function;

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

    /** Collapses either outcome into one value, so callers handle both cases without casting. */
    default <R> R fold(Function<? super E, ? extends R> onErr, Function<? super A, ? extends R> onOk) {
        return switch (this) {
            case Ok<E, A> ok -> onOk.apply(ok.value());
            case Err<E, A> err -> onErr.apply(err.error());
        };
    }
}

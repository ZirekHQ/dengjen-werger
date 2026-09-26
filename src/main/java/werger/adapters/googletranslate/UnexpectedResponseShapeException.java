package werger.adapters.googletranslate;

/** Google answered successfully, but with a body that isn't the v2 translate envelope. */
public class UnexpectedResponseShapeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnexpectedResponseShapeException(String message, Throwable cause) {
        super(message, cause);
    }
}

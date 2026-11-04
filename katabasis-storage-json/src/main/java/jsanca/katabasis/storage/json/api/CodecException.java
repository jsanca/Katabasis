package jsanca.katabasis.storage.json.api;

/**
 * Thrown when encoding or decoding a document fails due to structural, schema, or
 * migration errors.
 */
public final class CodecException extends RuntimeException {

    public CodecException(final String message) {
        super(message);
    }

    public CodecException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

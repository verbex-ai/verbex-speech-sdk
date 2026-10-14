package ai.verbex.speech.sdk.error;

/**
 * Base of every error the SDK raises (spec {@code VerbexError}). Unchecked.
 * Messages never contain the API key or an upload URL.
 */
public class VerbexException extends RuntimeException {
    public VerbexException(String message) {
        super(message);
    }

    public VerbexException(String message, Throwable cause) {
        super(message, cause);
    }
}

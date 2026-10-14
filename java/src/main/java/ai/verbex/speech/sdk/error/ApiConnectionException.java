package ai.verbex.speech.sdk.error;

/** No response: DNS, connect, TLS or a reset connection (spec {@code ConnectionError}). */
public class ApiConnectionException extends VerbexException {
    public ApiConnectionException(String message) {
        super(message);
    }
}

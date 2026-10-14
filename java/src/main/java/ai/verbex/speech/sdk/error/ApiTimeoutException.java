package ai.verbex.speech.sdk.error;

/** No response within the timeout (spec {@code TimeoutError}). */
public class ApiTimeoutException extends VerbexException {
    public ApiTimeoutException(String message) {
        super(message);
    }
}

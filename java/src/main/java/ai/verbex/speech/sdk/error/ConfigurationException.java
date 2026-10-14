package ai.verbex.speech.sdk.error;

/** Missing or invalid API key or base URL at construction (spec {@code ConfigurationError}). */
public class ConfigurationException extends VerbexException {
    public ConfigurationException(String message) {
        super(message);
    }
}

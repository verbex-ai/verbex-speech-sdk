package ai.verbex.speech.sdk.error;

/** One offending field of a {@code validation_failed} answer. */
public record FieldError(String name, String reason) {
}

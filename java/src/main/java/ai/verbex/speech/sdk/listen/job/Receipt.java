package ai.verbex.speech.sdk.listen.job;

/** The answer to a submit: {@code queuePosition} may be {@code null}. */
public record Receipt(String jobId, String status, Integer queuePosition) {
}

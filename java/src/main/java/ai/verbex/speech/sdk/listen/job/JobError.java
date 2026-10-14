package ai.verbex.speech.sdk.listen.job;

/** Why a job failed. */
public record JobError(String code, String message) {
}

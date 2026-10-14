package ai.verbex.speech.sdk.listen.job;

/**
 * The answer to a submit: {@code queuePosition} may be {@code null}. {@code replayed} is true when the
 * API answered with {@code Idempotent-Replayed: true}: the job was made by an earlier request with the
 * same idempotency key, and nothing was submitted now.
 */
public record Receipt(String jobId, String status, Integer queuePosition, boolean replayed) {

    /** A receipt that was not replayed. */
    public Receipt(String jobId, String status, Integer queuePosition) {
        this(jobId, status, queuePosition, false);
    }
}

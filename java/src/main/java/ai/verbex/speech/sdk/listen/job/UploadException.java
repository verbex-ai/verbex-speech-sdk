package ai.verbex.speech.sdk.listen.job;

import ai.verbex.speech.sdk.error.VerbexException;

/**
 * The storage {@code PUT} of the upload flow failed (spec {@code UploadError}). {@link #status()}
 * is the storage service's status, or {@code 0} when no answer arrived. {@code 412} means the
 * upload URL was already used. Never retried automatically: start the upload again.
 */
public class UploadException extends VerbexException {
    private final int status;

    public UploadException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

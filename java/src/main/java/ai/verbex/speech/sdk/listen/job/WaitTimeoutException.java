package ai.verbex.speech.sdk.listen.job;

import ai.verbex.speech.sdk.error.VerbexException;


/** {@code waitFor} ran past its timeout (spec {@code WaitTimeoutError}). */
public class WaitTimeoutException extends VerbexException {
    private final transient Job lastJob;

    public WaitTimeoutException(String message, Job lastJob) {
        super(message);
        this.lastJob = lastJob;
    }

    /** The last job read before the timeout, or {@code null} if none was read. */
    public Job lastJob() {
        return lastJob;
    }
}

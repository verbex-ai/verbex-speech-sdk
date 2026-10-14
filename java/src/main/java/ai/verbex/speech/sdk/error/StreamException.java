package ai.verbex.speech.sdk.error;

import java.util.Set;

/**
 * A WebSocket session ended by an error (spec {@code StreamError}). {@link #retryable()} says
 * whether reconnecting and resuming makes sense.
 */
public class StreamException extends VerbexException {
    private static final Set<Integer> RETRYABLE_CLOSE_CODES = Set.of(1006, 1011, 1013);
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "stream_failed", "service_unavailable", "credit_unavailable", "engine_unavailable");

    private final String code;
    private final String streamMessage;
    private final Integer closeCode;

    public StreamException(String code, String message, Integer closeCode) {
        super(code + ": " + message + (closeCode == null ? "" : " (close " + closeCode + ")"));
        this.code = code;
        this.streamMessage = message;
        this.closeCode = closeCode;
    }

    public String code() {
        return code;
    }

    public String streamMessage() {
        return streamMessage;
    }

    /** The WebSocket close code, or {@code null} when the session did not close with one. */
    public Integer closeCode() {
        return closeCode;
    }

    /**
     * True for close codes 1011, 1013 and 1006 (abnormal closure, no close frame), and for the
     * codes {@code stream_failed}, {@code service_unavailable}, {@code credit_unavailable},
     * {@code engine_unavailable}.
     */
    public boolean retryable() {
        return (closeCode != null && RETRYABLE_CLOSE_CODES.contains(closeCode))
                || (code != null && RETRYABLE_CODES.contains(code));
    }
}

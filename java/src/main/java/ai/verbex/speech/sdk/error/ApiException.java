package ai.verbex.speech.sdk.error;

import java.util.List;

/**
 * An HTTP error response (spec {@code ApiError}). Subtypes are chosen by status; any other
 * status is an {@code ApiException} itself.
 */
public class ApiException extends VerbexException {
    private final int status;
    private final String code;
    private final String apiMessage;
    private final String requestId;
    private final List<FieldError> fields;
    private final Long retryAfter;

    public ApiException(int status, String code, String message, String requestId,
                        List<FieldError> fields, Long retryAfter) {
        super("HTTP " + status + " " + code + ": " + message
                + (requestId == null ? "" : " (request_id " + requestId + ")"));
        this.status = status;
        this.code = code;
        this.apiMessage = message;
        this.requestId = requestId;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.retryAfter = retryAfter;
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The API's error code, or {@code http_<status>} for a body that is not the API's JSON. */
    public String code() {
        return code;
    }

    /** The API's human-readable message. */
    public String apiMessage() {
        return apiMessage;
    }

    /** {@code error.request_id} from the body, else the response's {@code X-Trace-Id}. */
    public String requestId() {
        return requestId;
    }

    /** Offending fields; empty when none. */
    public List<FieldError> fields() {
        return fields;
    }

    /** Seconds from {@code Retry-After}, or {@code null}. */
    public Long retryAfter() {
        return retryAfter;
    }
}

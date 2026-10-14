package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 429. */
public class RateLimitException extends ApiException {
    public RateLimitException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

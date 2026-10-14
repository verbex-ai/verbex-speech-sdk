package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 404. */
public class NotFoundException extends ApiException {
    public NotFoundException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

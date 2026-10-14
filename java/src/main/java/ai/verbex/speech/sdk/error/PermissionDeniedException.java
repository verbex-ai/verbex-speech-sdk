package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 403. */
public class PermissionDeniedException extends ApiException {
    public PermissionDeniedException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

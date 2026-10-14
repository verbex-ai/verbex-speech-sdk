package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 400, 405, 406, 409, 413, 415. */
public class BadRequestException extends ApiException {
    public BadRequestException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

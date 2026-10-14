package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 5xx. */
public class ServerException extends ApiException {
    public ServerException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

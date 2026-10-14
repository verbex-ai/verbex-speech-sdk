package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 401. */
public class AuthenticationException extends ApiException {
    public AuthenticationException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

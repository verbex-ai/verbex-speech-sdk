package ai.verbex.speech.sdk.error;

import java.util.List;

/** HTTP 402. */
public class PaymentRequiredException extends ApiException {
    public PaymentRequiredException(int status, String code, String message, String requestId,
            List<FieldError> fields, Long retryAfter) {
        super(status, code, message, requestId, fields, retryAfter);
    }
}

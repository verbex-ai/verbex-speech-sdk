package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.AuthenticationException;
import ai.verbex.speech.sdk.error.BadRequestException;
import ai.verbex.speech.sdk.error.FieldError;
import ai.verbex.speech.sdk.error.NotFoundException;
import ai.verbex.speech.sdk.error.PaymentRequiredException;
import ai.verbex.speech.sdk.error.PermissionDeniedException;
import ai.verbex.speech.sdk.error.RateLimitException;
import ai.verbex.speech.sdk.error.ServerException;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Maps an HTTP error response to the §7 type. */
final class Errors {
    private Errors() {
    }

    /**
     * @param header looks up a response header case-insensitively ({@code null} when absent)
     */
    static ApiException fromResponse(int status, Function<String, String> header, byte[] body) {
        String code = "http_" + status;
        String message = "The API answered HTTP " + status + ".";
        String requestId = null;
        List<FieldError> fields = new ArrayList<>();
        JsonNode root = body == null || body.length == 0 ? null : Json.tree(body);
        JsonNode error = root == null ? null : root.get("error");
        if (error != null && error.isObject()) {
            if (error.path("code").isTextual()) {
                code = error.get("code").asText();
            }
            if (error.path("message").isTextual()) {
                message = error.get("message").asText();
            }
            if (error.path("request_id").isTextual()) {
                requestId = error.get("request_id").asText();
            }
            for (JsonNode f : error.path("fields")) {
                fields.add(new FieldError(f.path("name").asText(null), f.path("reason").asText(null)));
            }
        }
        if (requestId == null) {
            requestId = header.apply("X-Trace-Id");
        }
        Long retryAfter = parseRetryAfter(header.apply("Retry-After"));
        return create(status, code, message, requestId, fields, retryAfter);
    }

    static ApiException create(int status, String code, String message, String requestId,
                               List<FieldError> fields, Long retryAfter) {
        switch (status) {
            case 400, 405, 406, 409, 413, 415:
                return new BadRequestException(status, code, message, requestId, fields, retryAfter);
            case 401:
                return new AuthenticationException(status, code, message, requestId, fields, retryAfter);
            case 402:
                return new PaymentRequiredException(status, code, message, requestId, fields, retryAfter);
            case 403:
                return new PermissionDeniedException(status, code, message, requestId, fields, retryAfter);
            case 404:
                return new NotFoundException(status, code, message, requestId, fields, retryAfter);
            case 429:
                return new RateLimitException(status, code, message, requestId, fields, retryAfter);
            default:
                if (status >= 500 && status <= 599) {
                    return new ServerException(status, code, message, requestId, fields, retryAfter);
                }
                return new ApiException(status, code, message, requestId, fields, retryAfter);
        }
    }

    /** Seconds only; an HTTP-date or garbage is ignored. */
    static Long parseRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : seconds;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

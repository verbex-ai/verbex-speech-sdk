package ai.verbex.speech.sdk.speak;

import ai.verbex.speech.sdk.error.FieldError;
import ai.verbex.speech.sdk.error.VerbexException;

import java.util.List;

/**
 * One utterance failed (spec {@code SpeakError}): an error frame on the speak socket, or a
 * {@code POST /v1/speak} body that ended with a transport error (code {@code audio_incomplete}).
 */
public class SpeakException extends VerbexException {
    private final String code;
    private final String speakMessage;
    private final String requestId;
    private final List<FieldError> fields;

    public SpeakException(String code, String message, String requestId, List<FieldError> fields) {
        super(code + ": " + message + (requestId == null ? "" : " (request_id " + requestId + ")"));
        this.code = code;
        this.speakMessage = message;
        this.requestId = requestId;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public String code() {
        return code;
    }

    public String speakMessage() {
        return speakMessage;
    }

    public String requestId() {
        return requestId;
    }

    public List<FieldError> fields() {
        return fields;
    }
}

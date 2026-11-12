package ai.verbex.speech.sdk.speak.synthesis;

/**
 * A whole utterance: the audio, its content type, and the request id from {@code X-Request-Id}.
 * {@code replayed} is true when the API answered with {@code Idempotent-Replayed: true}: an earlier
 * request with the same idempotency key was billed, and this one was spoken again without charge.
 */
public record SpeakAudio(byte[] audio, String contentType, String requestId, boolean replayed) {

    /** Audio that was not replayed. */
    public SpeakAudio(byte[] audio, String contentType, String requestId) {
        this(audio, contentType, requestId, false);
    }

    @Override
    public String toString() {
        return "SpeakAudio[bytes=" + (audio == null ? 0 : audio.length) + ", contentType=" + contentType
                + ", requestId=" + requestId + ", replayed=" + replayed + "]";
    }
}

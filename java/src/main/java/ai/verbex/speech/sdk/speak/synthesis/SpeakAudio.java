package ai.verbex.speech.sdk.speak.synthesis;

/** A whole utterance: the audio, its content type, and the request id from {@code X-Request-Id}. */
public record SpeakAudio(byte[] audio, String contentType, String requestId) {
    @Override
    public String toString() {
        return "SpeakAudio[bytes=" + (audio == null ? 0 : audio.length) + ", contentType=" + contentType
                + ", requestId=" + requestId + "]";
    }
}

package ai.verbex.speech.sdk.speak.stream;

/** One utterance from the speak socket: its PCM audio (16 kHz, mono, s16le) and the done frame's fields. */
public record SayResult(byte[] audio, String requestId, Double audioSeconds) {
    @Override
    public String toString() {
        return "SayResult[bytes=" + (audio == null ? 0 : audio.length) + ", requestId=" + requestId
                + ", audioSeconds=" + audioSeconds + "]";
    }
}

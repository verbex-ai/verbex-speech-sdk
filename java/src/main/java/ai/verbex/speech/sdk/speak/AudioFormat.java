package ai.verbex.speech.sdk.speak;

/** The audio format of {@code POST /v1/speak}. */
public enum AudioFormat {
    /** Raw PCM: 16 kHz, mono, signed 16-bit little-endian. */
    PCM("pcm"),
    /** The same PCM after a 44-byte WAV header whose size fields are 0xFFFFFFFF ("unknown"). */
    WAV("wav");

    private final String wire;

    AudioFormat(String wire) {
        this.wire = wire;
    }

    /** The value sent as {@code format}. */
    public String wire() {
        return wire;
    }
}

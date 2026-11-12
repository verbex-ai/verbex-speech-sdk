package ai.verbex.speech.sdk.speak.synthesis;

import ai.verbex.speech.sdk.speak.SpeakException;


import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The audio of one utterance as it arrives ({@code speak().synthesizeStream(...)}). The headers are
 * known before the first byte. A body that ends with a transport error raises {@link SpeakException}
 * (unchecked) from {@code read}, never a short read followed by end-of-stream. Close it when done.
 */
public final class SpeakAudioStream extends FilterInputStream {
    private final String contentType;
    private final String requestId;
    private final boolean replayed;

    SpeakAudioStream(InputStream in, String contentType, String requestId, boolean replayed) {
        super(in);
        this.contentType = contentType;
        this.requestId = requestId;
        this.replayed = replayed;
    }

    /** {@code audio/L16;rate=16000;channels=1} or {@code audio/wav}. */
    public String contentType() {
        return contentType;
    }

    /** From {@code X-Request-Id}: your request id or the generated one. */
    public String requestId() {
        return requestId;
    }

    /**
     * True when the API answered with {@code Idempotent-Replayed: true}: an earlier request with the
     * same idempotency key was billed, and this one is spoken again without charge.
     */
    public boolean replayed() {
        return replayed;
    }

    @Override
    public int read() {
        try {
            return super.read();
        } catch (IOException e) {
            throw broken();
        }
    }

    @Override
    public int read(byte[] b, int off, int len) {
        try {
            return super.read(b, off, len);
        } catch (IOException e) {
            throw broken();
        }
    }

    @Override
    public long skip(long n) {
        try {
            return super.skip(n);
        } catch (IOException e) {
            throw broken();
        }
    }

    private SpeakException broken() {
        return new SpeakException("audio_incomplete",
                "The audio ended with a transport error; treat the utterance as failed.", requestId, null);
    }
}

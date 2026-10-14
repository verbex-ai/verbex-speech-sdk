package ai.verbex.speech.sdk.listen.stream;

import ai.verbex.speech.sdk.error.ApiTimeoutException;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.Json;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.transport.WsConnection;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A live transcription session ({@code listen().stream(...)}, spec §4.6). Open once the server's
 * {@code ready} arrives. Send raw PCM (16 kHz, mono, s16le) at the pace it is produced, call
 * {@link #finish()} when the audio ends, and read events with the blocking {@link #nextEvent()}
 * until it returns {@code null}.
 *
 * <p>{@code send}/{@code configure}/{@code finish} may be called from one thread while another reads
 * {@code nextEvent()}. {@link #close()} without {@link #finish()} sends {@code eof}, waits up to 10 s
 * for {@code done} (discarding events not yet read), and closes.
 */
public final class ListenSession implements AutoCloseable {
    /** Larger audio frames are split. */
    public static final int MAX_FRAME_BYTES = 65_536;
    static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final WsConnection conn;
    private final Object readLock = new Object();
    private volatile String sessionId;
    private volatile boolean finished;
    private volatile boolean done;
    private volatile boolean closed;
    private volatile StreamException terminal;
    private ListenEvent.Error lastError;

    private ListenSession(WsConnection conn) {
        this.conn = conn;
    }

    static ListenSession open(HttpTransport transport, java.net.URI uri) {
        WsConnection conn = WsConnection.open(transport, uri);
        ListenSession session = new ListenSession(conn);
        try {
            session.awaitReady(transport.timeout());
            return session;
        } catch (RuntimeException e) {
            conn.close();
            throw e;
        }
    }

    private void awaitReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            WsConnection.Frame f = conn.poll(Duration.ofNanos(deadline - System.nanoTime()));
            if (f == null) {
                throw new ApiTimeoutException("The live session did not become ready within the timeout.");
            }
            if (f instanceof WsConnection.Text t) {
                JsonNode msg = Json.tree(t.text());
                String type = msg == null ? null : msg.path("type").asText(null);
                if ("ready".equals(type)) {
                    sessionId = msg.path("session_id").asText(null);
                    return;
                }
                if ("error".equals(type)) {
                    lastError = new ListenEvent.Error(msg.path("code").asText(null), msg.path("message").asText(null));
                }
            } else if (f instanceof WsConnection.Closed c) {
                throw ended(c.code());
            }
        }
    }

    /** From the {@code ready} frame; quote it to support. */
    public String sessionId() {
        return sessionId;
    }

    /** Sends raw PCM as binary frames of at most 64 KiB. */
    public void send(byte[] pcm) {
        send(pcm, 0, pcm.length);
    }

    /** Sends {@code length} bytes of {@code pcm} from {@code offset}. */
    public void send(byte[] pcm, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("send after finish()");
        }
        checkOpen();
        for (int pos = offset; pos < offset + length; pos += MAX_FRAME_BYTES) {
            conn.sendBinary(pcm, pos, Math.min(MAX_FRAME_BYTES, offset + length - pos));
        }
    }

    /** Replaces the hotwords; the answer arrives as a {@link ListenEvent.Configured} or {@link ListenEvent.Error}. */
    public void configure(List<String> hotwords) {
        checkOpen();
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "configure");
        frame.put("hotwords", hotwords == null ? List.of() : hotwords);
        conn.sendText(new String(Json.write(frame), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Sends {@code eof}: the server delivers the remaining segments, then {@code done}. */
    public void finish() {
        if (finished) {
            return;
        }
        checkOpen();
        finished = true;
        conn.sendText("{\"type\":\"eof\"}");
    }

    /**
     * Blocks for the next event. Returns {@code null} once {@code done} has been delivered.
     *
     * @throws StreamException when the session ends without {@code done}: with the code of a preceding
     *                         error frame, else {@code stream_closed}; and again on later calls
     */
    public ListenEvent nextEvent() {
        synchronized (readLock) {
            if (done) {
                return null;
            }
            if (terminal != null) {
                throw terminal;
            }
            while (true) {
                WsConnection.Frame f = conn.take();
                ListenEvent event = handle(f);
                if (event != null) {
                    return event;
                }
            }
        }
    }

    private ListenEvent handle(WsConnection.Frame f) {
        if (f instanceof WsConnection.Closed c) {
            terminal = ended(c.code());
            throw terminal;
        }
        if (!(f instanceof WsConnection.Text t)) {
            return null;
        }
        JsonNode msg = Json.tree(t.text());
        if (msg == null) {
            return null;
        }
        switch (msg.path("type").asText("")) {
            case "segment":
                return new ListenEvent.SegmentEvent(msg.path("index").asInt(), msg.path("start_sec").asDouble(),
                        msg.path("end_sec").asDouble(), msg.path("text").asText(""));
            case "configured": {
                List<String> words = new ArrayList<>();
                msg.path("hotwords").forEach(w -> words.add(w.asText()));
                return new ListenEvent.Configured(List.copyOf(words));
            }
            case "done":
                done = true;
                return new ListenEvent.Done(msg.path("total_segments").asInt());
            case "error":
                lastError = new ListenEvent.Error(msg.path("code").asText(null), msg.path("message").asText(null));
                return lastError;
            default:
                return null;
        }
    }

    private StreamException ended(int closeCode) {
        if (lastError != null) {
            return new StreamException(lastError.code(), lastError.message(), closeCode);
        }
        return new StreamException("stream_closed", "The server closed the session without done.", closeCode);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("the session is closed");
        }
        if (terminal != null) {
            throw terminal;
        }
    }

    /** Ends the session: sends {@code eof} if needed, waits up to 10 s for {@code done}, then closes. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            synchronized (readLock) {
                if (!done && terminal == null && !conn.isClosed()) {
                    try {
                        finish();
                    } catch (RuntimeException e) {
                        return;
                    }
                    long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
                    while (!done) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            break;
                        }
                        WsConnection.Frame f = conn.poll(Duration.ofNanos(left));
                        if (f == null || f instanceof WsConnection.Closed) {
                            break;
                        }
                        handle(f);
                    }
                }
            }
        } finally {
            closed = true;
            conn.close();
        }
    }
}

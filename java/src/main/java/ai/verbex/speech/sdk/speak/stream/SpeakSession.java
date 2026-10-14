package ai.verbex.speech.sdk.speak.stream;

import ai.verbex.speech.sdk.speak.SpeakException;
import ai.verbex.speech.sdk.speak.SpeakRequest;

import ai.verbex.speech.sdk.error.FieldError;
import ai.verbex.speech.sdk.transport.Json;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.transport.WsConnection;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Many utterances over one socket ({@code speak().stream()}, spec §5.3). Usable once open (no ready
 * frame). Utterances are strictly one at a time: a {@code say} while another is in progress throws
 * {@link IllegalStateException}. An utterance error raises {@link SpeakException} and the session
 * stays open; an error that closes the socket raises {@link StreamException} now and on every later
 * {@code say}. {@code say} blocks until the utterance's {@code done} or error arrives.
 */
public final class SpeakSession implements AutoCloseable {
    private final WsConnection conn;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile StreamException terminal;
    private volatile boolean closed;
    private JsonNode pendingError;

    SpeakSession(WsConnection conn) {
        this.conn = conn;
    }

    /** Speaks one utterance and returns all its audio. */
    public SayResult say(String model, String voice, String text) {
        return say(model, voice, text, null, null);
    }

    /**
     * Speaks one utterance and returns all its audio.
     *
     * @param requestId your id, or {@code null} for a generated UUID
     * @param settings  generation settings sent as top-level fields, or {@code null}
     */
    public SayResult say(String model, String voice, String text, String requestId, Map<String, ?> settings) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        SpeakDone done = say(model, voice, text, requestId, settings, audio::writeBytes);
        return new SayResult(audio.toByteArray(), done.requestId(), done.audioSeconds());
    }

    /**
     * Speaks one utterance, handing each PCM frame to {@code onAudio} as it arrives (on the calling
     * thread), and returns the done frame. Audio handed over before a {@link SpeakException}
     * belongs to the failed utterance.
     */
    public SpeakDone say(String model, String voice, String text, String requestId, Map<String, ?> settings,
                         Consumer<byte[]> onAudio) {
        Objects.requireNonNull(onAudio, "onAudio");
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("another utterance is in progress on this session");
        }
        try {
            if (closed) {
                throw new IllegalStateException("the session is closed");
            }
            if (terminal != null) {
                throw terminal;
            }
            String id = requestId != null ? requestId : UUID.randomUUID().toString();
            byte[] frame = Json.write(SpeakRequest.body(model, voice, text, id, settings));
            conn.sendText(new String(frame, StandardCharsets.UTF_8));
            while (true) {
                WsConnection.Frame f = conn.take();
                if (f instanceof WsConnection.Binary b) {
                    onAudio.accept(b.data());
                } else if (f instanceof WsConnection.Closed c) {
                    terminal = pendingError != null
                            ? new StreamException(pendingError.path("code").asText(null),
                            pendingError.path("message").asText(null), c.code())
                            : new StreamException("stream_closed", "The server closed the session.", c.code());
                    throw terminal;
                } else if (f instanceof WsConnection.Text t) {
                    JsonNode msg = Json.tree(t.text());
                    if (msg == null) {
                        continue;
                    }
                    String event = msg.path("event").asText("");
                    String answerId = msg.path("request_id").asText(null);
                    if ("done".equals(event) && (answerId == null || answerId.equals(id))) {
                        double seconds = msg.path("audio_seconds").asDouble();
                        return new SpeakDone(answerId == null ? id : answerId,
                                msg.has("audio_seconds") ? seconds : null);
                    }
                    if ("error".equals(event)) {
                        if (id.equals(answerId)) {
                            throw new SpeakException(msg.path("code").asText(null), msg.path("message").asText(null),
                                    answerId, fields(msg));
                        }
                        // Not about this utterance: a session error, usually followed by a close.
                        pendingError = msg;
                    }
                }
            }
        } finally {
            busy.set(false);
        }
    }

    private static List<FieldError> fields(JsonNode msg) {
        List<FieldError> out = new ArrayList<>();
        for (JsonNode f : msg.path("fields")) {
            out.add(new FieldError(f.path("name").asText(null), f.path("reason").asText(null)));
        }
        return out;
    }

    /** Closes the socket (normal closure). */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            conn.close();
        }
    }
}

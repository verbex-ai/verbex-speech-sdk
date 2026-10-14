package ai.verbex.speech.sdk.listen.stream;

import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.listen.Listen;
import ai.verbex.speech.sdk.listen.job.Segment;
import ai.verbex.speech.sdk.transport.HttpTransport;

import java.util.ArrayList;
import java.util.List;

/** Live transcription over {@code /v1/listen/stream} (spec §4.6). Use {@link Listen}. */
public final class LiveSessions {
    private final HttpTransport transport;

    /** Made by {@link Listen}; use {@code client.listen()}. Not part of the API. */
    public LiveSessions(HttpTransport transport) {
        this.transport = transport;
    }

    /** Opens a live session without hotwords or tags. */
    public ListenSession stream(String model, String language) {
        return stream(model, language, null, null);
    }

    /**
     * Opens {@code /v1/listen/stream} and returns once the {@code ready} frame has arrived.
     *
     * @throws ApiException    when the handshake is refused with an HTTP status
     * @throws StreamException when the server refuses the session after the handshake
     */
    public ListenSession stream(String model, String language, List<String> hotwords, List<String> tags) {
        if (model == null || language == null) {
            throw new IllegalArgumentException("model and language are required");
        }
        List<String[]> query = new ArrayList<>();
        query.add(new String[]{"model", model});
        query.add(new String[]{"language", language});
        if (hotwords != null) {
            for (String h : hotwords) {
                query.add(new String[]{"hotwords", h});
            }
        }
        query.addAll(HttpTransport.tagQuery(tags));
        return ListenSession.open(transport, transport.webSocketUri("/v1/listen/stream", query));
    }

    /**
     * Opens a session, sends every chunk, finishes, and returns all segments in order. It does not
     * pace the audio: an iterable that paces itself gives live results.
     */
    public List<Segment> transcribeStream(Iterable<byte[]> pcmChunks, String model, String language,
                                          List<String> hotwords, List<String> tags) {
        List<Segment> segments = new ArrayList<>();
        try (ListenSession session = stream(model, language, hotwords, tags)) {
            for (byte[] chunk : pcmChunks) {
                session.send(chunk);
            }
            session.finish();
            ListenEvent event;
            while ((event = session.nextEvent()) != null) {
                if (event instanceof ListenEvent.SegmentEvent s) {
                    segments.add(s.toSegment());
                }
            }
        }
        segments.sort(java.util.Comparator.comparingInt(Segment::index));
        return segments;
    }
}

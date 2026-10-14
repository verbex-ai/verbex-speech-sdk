package ai.verbex.speech.sdk.speak.stream;

import ai.verbex.speech.sdk.speak.Speak;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.WsConnection;

import java.util.List;

/** Many utterances over one socket: {@code /v1/speak/stream} (spec §5.3). Use {@link Speak}. */
public final class SpeakSessions {
    private final HttpTransport transport;

    /** Made by {@link Speak}; use {@code client.speak()}. Not part of the API. */
    public SpeakSessions(HttpTransport transport) {
        this.transport = transport;
    }

    public SpeakSession open(List<String> tags) {
        return new SpeakSession(WsConnection.open(transport,
                transport.webSocketUri("/v1/speak/stream", HttpTransport.tagQuery(tags))));
    }
}

package ai.verbex.speech.sdk.speak.voice;

import ai.verbex.speech.sdk.speak.Speak;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.Json;

import java.util.List;

/** The text-to-speech voices: {@code GET /v1/speak/voices}. Use {@link Speak#voices()}. */
public final class Voices {
    record VoiceList(List<Voice> items) {
        List<Voice> itemsOrEmpty() {
            return items == null ? List.of() : items;
        }
    }

    private final HttpTransport transport;

    /** Made by {@link Speak}; use {@code client.speak()}. Not part of the API. */
    public Voices(HttpTransport transport) {
        this.transport = transport;
    }

    public List<Voice> list() {
        return parse(transport.get("/v1/speak/voices"));
    }

    static List<Voice> parse(byte[] body) {
        return Json.read(body, VoiceList.class).itemsOrEmpty();
    }
}

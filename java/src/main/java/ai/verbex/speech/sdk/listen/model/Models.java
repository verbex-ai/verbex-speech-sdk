package ai.verbex.speech.sdk.listen.model;

import ai.verbex.speech.sdk.listen.Listen;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.Json;

import java.util.List;

/** The speech-to-text models: {@code GET /v1/listen/models}. Use {@link Listen#models()}. */
public final class Models {
    record ModelList(List<SttModel> items) {
        List<SttModel> itemsOrEmpty() {
            return items == null ? List.of() : items;
        }
    }

    private final HttpTransport transport;

    /** Made by {@link Listen}; use {@code client.listen()}. Not part of the API. */
    public Models(HttpTransport transport) {
        this.transport = transport;
    }

    public List<SttModel> list() {
        return parse(transport.get("/v1/listen/models"));
    }

    static List<SttModel> parse(byte[] body) {
        return Json.read(body, ModelList.class).itemsOrEmpty();
    }
}

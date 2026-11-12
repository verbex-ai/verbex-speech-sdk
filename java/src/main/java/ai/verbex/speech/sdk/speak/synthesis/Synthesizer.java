package ai.verbex.speech.sdk.speak.synthesis;

import ai.verbex.speech.sdk.speak.Speak;
import ai.verbex.speech.sdk.speak.SpeakException;
import ai.verbex.speech.sdk.speak.SpeakRequest;
import ai.verbex.speech.sdk.transport.CallKind;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.Idempotency;
import ai.verbex.speech.sdk.transport.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** One utterance over HTTP: {@code POST /v1/speak} (spec §5.2). Use {@link Speak}. */
public final class Synthesizer {
    /** Speak timeout: the gateway's limit, counted until the first audio arrives. */
    static final Duration SPEAK_TIMEOUT = Duration.ofSeconds(360);

    private final HttpTransport transport;

    /** Made by {@link Speak}; use {@code client.speak()}. Not part of the API. */
    public Synthesizer(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * The whole utterance. Every call sends an {@code Idempotency-Key}: the request's own, else a random
     * UUID made once for this call, the same for each retry. A retry after a lost answer is spoken again
     * without being billed again ({@link SpeakAudio#replayed()}).
     */
    public SpeakAudio synthesize(SpeakRequest request) {
        try (SpeakAudioStream stream = synthesizeStream(request)) {
            byte[] audio = stream.readAllBytes();
            return new SpeakAudio(audio, stream.contentType(), stream.requestId(), stream.replayed());
        } catch (IOException e) {
            throw new SpeakException("audio_incomplete", "The audio could not be read.", null, null);
        }
    }

    /** The utterance as it arrives; sends an {@code Idempotency-Key} as {@link #synthesize} does. */
    public SpeakAudioStream synthesizeStream(SpeakRequest request) {
        List<String[]> query = new ArrayList<>();
        query.add(new String[]{"format", request.format().wire()});
        query.addAll(HttpTransport.tagQuery(request.tags()));
        String path = "/v1/speak" + HttpTransport.queryString(query);
        byte[] body = Json.write(request.body());
        String key = Idempotency.keyFor(request.idempotencyKey());
        HttpResponse<InputStream> response = transport.send(CallKind.SPEAK, request.traceId(),
                trace -> transport.request(path, trace, SPEAK_TIMEOUT).header("Content-Type", "application/json")
                        .header(Idempotency.HEADER, key).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        return new SpeakAudioStream(response.body(), response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Request-Id").orElse(null), Idempotency.replayed(response.headers()));
    }
}

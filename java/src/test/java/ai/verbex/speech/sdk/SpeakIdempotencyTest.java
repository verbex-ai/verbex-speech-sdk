package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.BadRequestException;
import ai.verbex.speech.sdk.speak.SpeakRequest;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudioStream;
import ai.verbex.speech.sdk.transport.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ai.verbex.speech.sdk.FakeApi.error;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every speak carries an Idempotency-Key, the same one across its retries (spec §5.2). */
class SpeakIdempotencyTest {
    private static final Map<String, String> PCM = Map.of("Content-Type", "audio/L16;rate=16000;channels=1",
            "X-Request-Id", "r");
    private static final byte[] AUDIO = new byte[3200];

    private final FakeApi api = new FakeApi();
    private final TestClients.VirtualTime time = new TestClients.VirtualTime();

    @AfterEach
    void stop() {
        api.close();
    }

    private VerbexSpeech client() {
        return TestClients.builder(api.baseUrl(), time).build();
    }

    private static SpeakRequest.Builder hello() {
        return SpeakRequest.builder().model("m").voice("v").text("t");
    }

    private static FakeApi.Handler spoken(Map<String, String> extra) {
        Map<String, String> headers = new java.util.HashMap<>(PCM);
        headers.putAll(extra);
        return FakeApi.respond(200, headers, AUDIO);
    }

    /** Closes the connection without an answer: the client sees a connection error. */
    private static FakeApi.Handler reset() {
        return (ex, rec) -> {
            throw new IOException("reset");
        };
    }

    private List<String> keys() {
        return api.requests("POST", "/v1/speak").stream().map(r -> r.header("Idempotency-Key")).toList();
    }

    @Test
    void eachSynthesizeSendsANewUuidKey() {
        api.on("POST", "/v1/speak", spoken(Map.of()));
        client().speak().synthesize(hello().build());
        client().speak().synthesize(hello().build());

        Set<String> keys = new HashSet<>();
        for (String key : keys()) {
            assertNotNull(key, "every speak carries a key");
            assertEquals(4, UUID.fromString(key).version(), "a UUID v4");
            assertEquals(key, UUID.fromString(key).toString());
            keys.add(key);
        }
        assertEquals(2, keys.size(), "one key per synthesize call");
    }

    @Test
    void theSameKeyGoesWithEveryRetry() {
        api.on("POST", "/v1/speak", error(503, "service_unavailable", Map.of()), reset(), spoken(Map.of()));
        assertArrayEquals(AUDIO, client().speak().synthesize(hello().build()).audio());

        List<String> keys = keys();
        assertEquals(3, keys.size());
        assertNotNull(keys.get(0));
        assertEquals(1, new HashSet<>(keys).size(), "one key across the retries");
    }

    @Test
    void aSpeakWhoseFirstRequestStillSpeaksIsRetried() {
        api.on("POST", "/v1/speak", error(409, "request_in_progress", Map.of()), spoken(Map.of()));
        assertArrayEquals(AUDIO, client().speak().synthesize(hello().build()).audio());
        assertEquals(2, api.requests.size());
        assertEquals(keys().get(0), keys().get(1));
    }

    @Test
    void theCallersOwnKeyIsSentAsGivenAndNotInTheBody() {
        api.on("POST", "/v1/speak", spoken(Map.of()));
        client().speak().synthesize(hello().idempotencyKey("turn-42:utt-7").build());
        FakeApi.Recorded r = api.requests.get(0);
        assertEquals("turn-42:utt-7", r.header("Idempotency-Key"));
        assertFalse(r.bodyText().contains("turn-42"), "the key is a header only");
        assertEquals(3, Json.tree(r.body()).size());
    }

    @Test
    void synthesizeStreamSendsTheKeyToo() throws IOException {
        api.on("POST", "/v1/speak", spoken(Map.of()));
        try (SpeakAudioStream stream = client().speak().synthesizeStream(hello().idempotencyKey("k-1").build())) {
            stream.readAllBytes();
        }
        assertEquals("k-1", api.requests.get(0).header("Idempotency-Key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "tab\tkey", "キー", "line\n"})
    void aBadKeyIsRefusedBeforeAnythingIsSent(String bad) {
        api.on("POST", "/v1/speak", spoken(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> client().speak().synthesize(hello().idempotencyKey(bad).build()));
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void aKeyOver255CharactersIsRefusedAnd255IsSent() {
        api.on("POST", "/v1/speak", spoken(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> hello().idempotencyKey("k".repeat(256)).build());
        assertTrue(api.requests.isEmpty());
        client().speak().synthesize(hello().idempotencyKey("k".repeat(255)).build());
        assertEquals("k".repeat(255), api.requests.get(0).header("Idempotency-Key"));
    }

    @Test
    void replayedAudioSaysSo() throws IOException {
        api.on("POST", "/v1/speak", spoken(Map.of("Idempotent-Replayed", " TRUE ")),
                spoken(Map.of("Idempotent-Replayed", "true")), spoken(Map.of("Idempotent-Replayed", "false")),
                spoken(Map.of()));
        assertTrue(client().speak().synthesize(hello().build()).replayed());
        try (SpeakAudioStream stream = client().speak().synthesizeStream(hello().build())) {
            assertTrue(stream.replayed());
        }
        assertFalse(client().speak().synthesize(hello().build()).replayed());
        try (SpeakAudioStream stream = client().speak().synthesizeStream(hello().build())) {
            assertFalse(stream.replayed());
        }
        assertEquals(new SpeakAudio(AUDIO, "a", "r").replayed(), false, "the short form is not replayed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"idempotency_key_reused", "idempotency_replays_exhausted"})
    void aRefusedKeyIsABadRequestAndNotRetried(String code) {
        api.on("POST", "/v1/speak", error(422, code, Map.of()));
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> client().speak().synthesize(hello().idempotencyKey("k-1").build()));
        assertEquals(422, e.status());
        assertEquals(code, e.code());
        assertEquals(1, api.requests.size());
    }
}

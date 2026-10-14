package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.PaymentRequiredException;
import ai.verbex.speech.sdk.speak.SpeakException;
import ai.verbex.speech.sdk.transport.Json;

import ai.verbex.speech.sdk.speak.AudioFormat;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudioStream;
import ai.verbex.speech.sdk.speak.SpeakRequest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static ai.verbex.speech.sdk.FakeApi.error;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpeakHttpTest {
    private static final Map<String, String> PCM = Map.of("Content-Type", "audio/L16;rate=16000;channels=1",
            "X-Request-Id", "order-42");

    private final FakeApi api = new FakeApi();
    private final TestClients.VirtualTime time = new TestClients.VirtualTime();

    @AfterEach
    void stop() {
        api.close();
    }

    private VerbexSpeech client() {
        return TestClients.builder(api.baseUrl(), time).build();
    }

    private static byte[] pcm(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    private static SpeakRequest.Builder hello() {
        return SpeakRequest.builder().model("verbex_v1_bn").voice("maria_2").text("hello");
    }

    @Test
    void synthesizesOneUtterance() {
        api.on("POST", "/v1/speak", FakeApi.respond(200, PCM, pcm(6400)));
        SpeakAudio audio = client().speak().synthesize(hello().requestId("order-42")
                .settings(Map.of("temperature", 0.7)).build());
        assertArrayEquals(pcm(6400), audio.audio());
        assertEquals("audio/L16;rate=16000;channels=1", audio.contentType());
        assertEquals("order-42", audio.requestId());

        FakeApi.Recorded r = api.requests.get(0);
        assertEquals("format=pcm", r.query());
        assertEquals("application/json", r.header("Content-Type"));
        JsonNode body = Json.tree(r.body());
        assertEquals("verbex_v1_bn", body.get("model").asText());
        assertEquals("maria_2", body.get("voice").asText());
        assertEquals("hello", body.get("text").asText());
        assertEquals("order-42", body.get("request_id").asText());
        assertEquals(0.7, body.get("temperature").asDouble(), "settings are top-level fields");
        assertEquals(5, body.size());
    }

    @Test
    void sendsFormatAndTagsAndOmitsAnAbsentRequestId() {
        api.on("POST", "/v1/speak", FakeApi.respond(200, Map.of("Content-Type", "audio/wav"), pcm(10)));
        client().speak().synthesize(hello().format(AudioFormat.WAV).tags(List.of("a", "b c")).build());
        assertEquals("format=wav&tag=a&tag=b%20c", api.requests.get(0).query());
        assertEquals(false, Json.tree(api.requests.get(0).body()).has("request_id"));
    }

    @Test
    void aSettingMayNotReplaceACoreField() {
        assertThrows(IllegalArgumentException.class, () -> hello().settings(Map.of("text", "x")).build());
        assertThrows(IllegalArgumentException.class, () -> SpeakRequest.builder().voice("v").text("t").build());
    }

    @Test
    void streamsTheAudioWithHeadersFirst() throws IOException {
        api.on("POST", "/v1/speak", FakeApi.respond(200, PCM, pcm(70000)));
        try (SpeakAudioStream stream = client().speak().synthesizeStream(hello().build())) {
            assertEquals("order-42", stream.requestId());
            assertEquals("audio/L16;rate=16000;channels=1", stream.contentType());
            assertArrayEquals(pcm(70000), stream.readAllBytes());
        }
    }

    private static FakeApi.Handler truncated() {
        return (ex, rec) -> {
            ex.getResponseHeaders().add("Content-Type", "audio/L16;rate=16000;channels=1");
            ex.sendResponseHeaders(200, 10_000);
            OutputStream os = ex.getResponseBody();
            os.write(new byte[100]);
            os.flush();
            ex.getHttpContext().getServer();
            // Drop the connection mid-body.
            throw new IOException("cut");
        };
    }

    @Test
    void aBodyThatEndsWithATransportErrorIsASpeakErrorNeverShortAudio() {
        api.on("POST", "/v1/speak", truncated());
        SpeakException e = assertThrows(SpeakException.class, () -> client().speak().synthesize(hello().build()));
        assertEquals("audio_incomplete", e.code());
        assertEquals(1, api.requests.size(), "audio had started: no retry");
    }

    @Test
    void aStreamThatBreaksRaisesASpeakError() {
        api.on("POST", "/v1/speak", truncated());
        assertThrows(SpeakException.class, () -> {
            try (InputStream in = client().speak().synthesizeStream(hello().build())) {
                in.readAllBytes();
            }
        });
    }

    @Test
    void retriesBeforeAnyAudioAndMapsErrors() {
        api.on("POST", "/v1/speak", error(503, "engine_unavailable", Map.of()), FakeApi.respond(200, PCM, pcm(10)));
        assertEquals(10, client().speak().synthesize(hello().build()).audio().length);
        assertEquals(2, api.requests.size());

        try (FakeApi other = new FakeApi()) {
            other.on("POST", "/v1/speak", error(402, "insufficient_credit", Map.of()));
            VerbexSpeech c = TestClients.builder(other.baseUrl(), time).build();
            assertThrows(PaymentRequiredException.class, () -> c.speak().synthesize(hello().build()));
            assertThrows(PaymentRequiredException.class, () -> c.speak().synthesizeStream(hello().build()));
            assertEquals(2, other.requests.size());
        }
    }

    @Test
    void speakIsGivenMoreThanTheClientTimeout() {
        api.on("POST", "/v1/speak", FakeApi.delayed(700, FakeApi.respond(200, PCM, pcm(10))));
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).timeout(Duration.ofMillis(250)).build();
        assertEquals(10, c.speak().synthesize(hello().build()).audio().length);
    }
}

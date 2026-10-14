package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.FieldError;
import ai.verbex.speech.sdk.speak.SpeakException;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.transport.Json;

import ai.verbex.speech.sdk.speak.stream.SayResult;
import ai.verbex.speech.sdk.speak.stream.SpeakDone;
import ai.verbex.speech.sdk.speak.stream.SpeakSession;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakStreamTest {
    private static final JsonNode FIXTURE = Conformance.load("speak-stream.json");
    private ScriptedWsServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    private VerbexSpeech client() {
        return TestClients.builder(server.baseUrl(), new TestClients.VirtualTime()).build();
    }

    @Test
    void conformanceScript() {
        server = new ScriptedWsServer(FIXTURE.get("script"));
        JsonNode expect = FIXTURE.get("expect");
        try (SpeakSession session = client().speak().stream()) {
            SayResult one = session.say("verbex_v1_bn", "maria_2", "one", "turn-1", Map.of());
            assertEquals(expect.get("turn-1").get("audio_bytes").asInt(), one.audio().length);
            assertEquals(expect.get("turn-1").get("audio_seconds").asDouble(), one.audioSeconds());
            assertEquals("turn-1", one.requestId());

            SpeakException two = assertThrows(SpeakException.class,
                    () -> session.say("verbex_v1_bn", "maria_2", "", "turn-2", Map.of()));
            JsonNode e2 = expect.get("turn-2");
            assertEquals(e2.get("error").asText(), "SpeakError");
            assertEquals(e2.get("code").asText(), two.code());
            assertEquals("turn-2", two.requestId());
            assertEquals(List.of(new FieldError("text", "is required")), two.fields());
            assertTrue(e2.get("session_open").asBoolean());

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            List<Integer> chunks = new ArrayList<>();
            SpeakDone three = session.say("verbex_v1_bn", "maria_2", "three", "turn-3", Map.of(), chunk -> {
                chunks.add(chunk.length);
                audio.writeBytes(chunk);
            });
            assertEquals(expect.get("turn-3").get("audio_bytes").asInt(), audio.size());
            assertEquals(List.of(1600), chunks);
            assertEquals(expect.get("turn-3").get("audio_seconds").asDouble(), three.audioSeconds());
        }
        assertTrue(server.mismatches.isEmpty(), server.mismatches.toString());
        assertEquals("Token " + TestClients.KEY, server.authorization);
        assertEquals("/v1/speak/stream", server.resource);
    }

    @Test
    void generatesARequestIdWhenOmittedAndSendsSettingsAtTopLevel() throws Exception {
        server = ScriptedWsServer.of("""
                [{"client":{"text":"any"}},{"server":{"binary_bytes":320}},{"done_for_last":0.01}]""");
        try (SpeakSession session = client().speak().stream(List.of("env:test"))) {
            SayResult r = session.say("verbex_v1_bn", "maria_2", "hi", null, Map.of("temperature", 0.5));
            UUID.fromString(r.requestId());
            assertEquals(320, r.audio().length);
        }
        JsonNode sent = Json.tree((String) server.received.get(0));
        assertEquals(0.5, sent.get("temperature").asDouble());
        assertEquals(sent.get("request_id").asText().length(), 36);
        assertEquals("/v1/speak/stream?tag=env%3Atest", server.resource);
    }

    @Test
    void oneUtteranceAtATime() throws Exception {
        server = ScriptedWsServer.of("""
                [{"client":{"text":"any"}}]""");
        SpeakSession session = client().speak().stream();
        CompletableFuture<SayResult> first = CompletableFuture.supplyAsync(
                () -> session.say("verbex_v1_bn", "maria_2", "one", "a", Map.of()));
        for (int i = 0; i < 100 && server.received.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertThrows(IllegalStateException.class, () -> session.say("verbex_v1_bn", "maria_2", "two", "b", Map.of()));
        session.close();
        assertThrows(Exception.class, first::join);
    }

    @Test
    void anErrorFrameFollowedByACloseEndsTheSession() {
        server = ScriptedWsServer.of("""
                [{"client":{"text":"any"}},
                 {"server":{"text":{"event":"error","code":"service_unavailable","message":"Try again."}}},
                 {"close":1013}]""");
        try (SpeakSession session = client().speak().stream()) {
            StreamException e = assertThrows(StreamException.class,
                    () -> session.say("verbex_v1_bn", "maria_2", "one", "a", Map.of()));
            assertEquals("service_unavailable", e.code());
            assertEquals(1013, e.closeCode());
            assertTrue(e.retryable());
            StreamException again = assertThrows(StreamException.class,
                    () -> session.say("verbex_v1_bn", "maria_2", "two", "b", Map.of()));
            assertEquals("service_unavailable", again.code());
        }
    }
}

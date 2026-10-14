package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.AuthenticationException;
import ai.verbex.speech.sdk.error.StreamException;

import ai.verbex.speech.sdk.listen.stream.ListenEvent;
import ai.verbex.speech.sdk.listen.stream.ListenSession;
import ai.verbex.speech.sdk.listen.job.Segment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListenStreamTest {
    private static final JsonNode FIXTURE = Conformance.load("listen-stream.json");
    private static final String MODEL = "verbex_jp_stt_x1";

    private ScriptedWsServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    private VerbexSpeech client(ScriptedWsServer s) {
        return TestClients.builder(s.baseUrl(), new TestClients.VirtualTime()).build();
    }

    @Test
    void conformanceNormalSession() throws Exception {
        JsonNode normal = FIXTURE.get("normal");
        server = new ScriptedWsServer(normal.get("script"));
        List<String> hotwords = List.of(normal.get("query").get("hotwords").get(0).asText());
        List<ListenEvent> events = new ArrayList<>();
        try (ListenSession session = client(server).listen().stream(MODEL, "ja", hotwords, List.of("env:test"))) {
            assertEquals("314ba9e8-d962-4a04-88b9-06bc53dd8036", session.sessionId());
            session.send(new byte[3200]);
            session.configure(List.of());
            session.finish();
            ListenEvent e;
            while ((e = session.nextEvent()) != null) {
                events.add(e);
            }
            assertNull(session.nextEvent(), "stays ended");
        }
        assertEquals(List.of(
                new ListenEvent.Configured(List.of()),
                new ListenEvent.SegmentEvent(0, 0.1, 17.48, "おでんわありがとうございます。"),
                new ListenEvent.SegmentEvent(1, 17.48, 19.68, "おりかえしごれんらくいたします。"),
                new ListenEvent.Done(2)), events);
        assertTrue(server.mismatches.isEmpty(), server.mismatches.toString());
        assertEquals("Token " + TestClients.KEY, server.authorization);
        String query = URLDecoder.decode(server.resource.substring(server.resource.indexOf('?') + 1), StandardCharsets.UTF_8);
        assertEquals("model=verbex_jp_stt_x1&language=ja&hotwords=かいみょう&tag=env:test", query);
        assertTrue(server.resource.startsWith("/v1/listen/stream?"));
    }

    @Test
    void conformanceRefusedAtOpen() {
        JsonNode c = FIXTURE.get("refused");
        server = new ScriptedWsServer(c.get("script"));
        StreamException e = assertThrows(StreamException.class, () -> client(server).listen().stream(MODEL, "ja"));
        assertExpected(c.get("expect"), e);
    }

    @Test
    void conformanceFailedMidSession() {
        JsonNode c = FIXTURE.get("failed_mid_session");
        server = new ScriptedWsServer(c.get("script"));
        try (ListenSession session = client(server).listen().stream(MODEL, "ja")) {
            assertEquals("s-2", session.sessionId());
            int segments = 0;
            StreamException error = null;
            ListenEvent.Error errorEvent = null;
            try {
                ListenEvent e;
                while ((e = session.nextEvent()) != null) {
                    if (e instanceof ListenEvent.SegmentEvent) {
                        segments++;
                    } else if (e instanceof ListenEvent.Error err) {
                        errorEvent = err;
                    }
                }
            } catch (StreamException ex) {
                error = ex;
            }
            assertEquals(c.get("expect").get("segments_before_error").asInt(), segments);
            assertEquals("stream_failed", errorEvent.code());
            assertExpected(c.get("expect"), error);
            StreamException again = assertThrows(StreamException.class, session::nextEvent);
            assertEquals("stream_failed", again.code());
        }
    }

    @Test
    void conformanceClosedWithoutDone() {
        JsonNode c = FIXTURE.get("closed_without_done");
        server = new ScriptedWsServer(c.get("script"));
        try (ListenSession session = client(server).listen().stream(MODEL, "ja")) {
            StreamException e = assertThrows(StreamException.class, session::nextEvent);
            assertExpected(c.get("expect"), e);
        }
    }

    private static void assertExpected(JsonNode expect, StreamException e) {
        assertEquals(expect.get("error").asText(), "StreamError");
        assertEquals(expect.get("code").asText(), e.code());
        assertEquals(expect.get("close_code").asInt(), e.closeCode());
        assertEquals(expect.get("retryable").asBoolean(), e.retryable());
    }

    @Test
    void splitsLargeFramesAndRefusesSendingAfterFinish() throws Exception {
        server = ScriptedWsServer.of("""
                [{"server":{"type":"ready","session_id":"s"}},
                 {"client":{"binary_bytes":65536}},{"client":{"binary_bytes":65536}},{"client":{"binary_bytes":18928}},
                 {"client":{"text":{"type":"eof"}}},
                 {"server":{"type":"done","total_segments":0}},{"close":1000}]""");
        try (ListenSession session = client(server).listen().stream(MODEL, "ja")) {
            session.send(new byte[150_000]);
            session.finish();
            assertThrows(IllegalStateException.class, () -> session.send(new byte[10]));
            assertInstanceOf(ListenEvent.Done.class, session.nextEvent());
        }
        assertTrue(server.mismatches.isEmpty(), server.mismatches.toString());
    }

    @Test
    void closingWithoutFinishSendsEofAndWaitsForDone() throws Exception {
        server = ScriptedWsServer.of("""
                [{"server":{"type":"ready","session_id":"s"}},
                 {"client":{"binary_bytes":3200}},
                 {"client":{"text":{"type":"eof"}}},
                 {"server":{"type":"segment","index":0,"start_sec":0,"end_sec":0.1,"text":"x"}},
                 {"server":{"type":"done","total_segments":1}},{"close":1000}]""");
        try (ListenSession session = client(server).listen().stream(MODEL, "ja")) {
            session.send(new byte[3200]);
        }
        assertTrue(server.finished.await(5, TimeUnit.SECONDS));
        assertTrue(server.mismatches.isEmpty(), server.mismatches.toString());
    }

    @Test
    void answersPingsAutomatically() throws Exception {
        server = ScriptedWsServer.of("""
                [{"server":{"type":"ready","session_id":"s"}},{"ping":true},
                 {"client":{"text":{"type":"eof"}}},
                 {"server":{"type":"done","total_segments":0}},{"close":1000}]""");
        try (ListenSession session = client(server).listen().stream(MODEL, "ja")) {
            for (int i = 0; i < 50 && server.pongs.get() == 0; i++) {
                Thread.sleep(20);
            }
            session.finish();
            assertInstanceOf(ListenEvent.Done.class, session.nextEvent());
        }
        assertEquals(1, server.pongs.get());
    }

    @Test
    void configureErrorsArriveAsEventsAndTheSessionStaysOpen() throws Exception {
        server = ScriptedWsServer.of("""
                [{"server":{"type":"ready","session_id":"s"}},
                 {"client":{"text":{"type":"configure","hotwords":["x"]}}},
                 {"server":{"type":"error","code":"unsupported_feature","message":"no"}},
                 {"client":{"text":{"type":"eof"}}},
                 {"server":{"type":"done","total_segments":0}},{"close":1000}]""");
        try (ListenSession session = client(server).listen().stream(MODEL, "ja")) {
            session.configure(List.of("x"));
            assertEquals(new ListenEvent.Error("unsupported_feature", "no"), session.nextEvent());
            session.finish();
            assertInstanceOf(ListenEvent.Done.class, session.nextEvent());
            assertNull(session.nextEvent());
        }
    }

    @Test
    void transcribeStreamSendsFinishesAndReturnsSegmentsInOrder() {
        server = ScriptedWsServer.of("""
                [{"server":{"type":"ready","session_id":"s"}},
                 {"client":{"binary_bytes":6400}},{"client":{"binary_bytes":6400}},
                 {"server":{"type":"segment","index":0,"start_sec":0,"end_sec":0.2,"text":"a"}},
                 {"client":{"text":{"type":"eof"}}},
                 {"server":{"type":"segment","index":1,"start_sec":0.2,"end_sec":0.4,"text":"b"}},
                 {"server":{"type":"done","total_segments":2}},{"close":1000}]""");
        List<Segment> segments = client(server).listen().transcribeStream(
                List.of(new byte[6400], new byte[6400]), MODEL, "ja", null, null);
        assertEquals(List.of(new Segment(0, 0.0, 0.2, "a"), new Segment(1, 0.2, 0.4, "b")), segments);
        assertTrue(server.mismatches.isEmpty(), server.mismatches.toString());
    }

    @Test
    void aRefusedHandshakeRaisesTheHttpError() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket s = raw.accept()) {
                    InputStream in = s.getInputStream();
                    StringBuilder head = new StringBuilder();
                    while (!head.toString().endsWith("\r\n\r\n")) {
                        head.append((char) in.read());
                    }
                    String body = "{\"error\":{\"code\":\"unauthenticated\",\"message\":\"Authentication required.\",\"request_id\":\"r-1\"}}";
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nX-Trace-Id: r-1\r\n"
                            + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // the test asserts on the client side
                }
            });
            t.start();
            VerbexSpeech c = TestClients.builder("http://127.0.0.1:" + raw.getLocalPort(),
                    new TestClients.VirtualTime()).build();
            AuthenticationException e = assertThrows(AuthenticationException.class, () -> c.listen().stream(MODEL, "ja"));
            assertEquals(401, e.status());
            assertEquals("r-1", e.requestId());
            assertFalse(e.getMessage().contains(TestClients.KEY));
            t.join(5000);
        }
    }
}

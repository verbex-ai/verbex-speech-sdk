package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.ApiConnectionException;
import ai.verbex.speech.sdk.error.BadRequestException;
import ai.verbex.speech.sdk.error.ServerException;
import ai.verbex.speech.sdk.listen.job.UploadException;
import ai.verbex.speech.sdk.transport.Json;

import ai.verbex.speech.sdk.listen.job.Job;

import ai.verbex.speech.sdk.listen.job.Receipt;
import ai.verbex.speech.sdk.listen.job.TranscribeRequest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ai.verbex.speech.sdk.FakeApi.error;
import static ai.verbex.speech.sdk.FakeApi.json;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscribeTest {
    private static final JsonNode R = Conformance.load("responses.json");
    private static final String MODEL = "verbex_jp_stt_x1";
    private static final String RECEIPT = R.get("receipt").toString();
    private static final int MIB = 1_048_576;

    private final FakeApi api = new FakeApi();
    private final TestClients.VirtualTime time = new TestClients.VirtualTime();

    @TempDir
    Path tmp;

    @AfterEach
    void stop() {
        api.close();
    }

    private VerbexSpeech client() {
        return TestClients.builder(api.baseUrl(), time).build();
    }

    private String uploadStarted(int size) {
        return "{\"upload_id\":\"up1\",\"upload_url\":\"" + api.baseUrl() + "/storage/up1?X-Goog-Signature=secret\","
                + "\"method\":\"PUT\",\"headers\":{\"x-goog-if-generation-match\":\"0\","
                + "\"x-goog-content-length-range\":\"" + size + "," + size + "\"},"
                + "\"upload_url_expires_in_seconds\":600,\"submit_window_seconds\":1800}";
    }

    private static byte[] audio(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31);
        }
        return b;
    }

    @Test
    void aSmallFileGoesInTheRequestAsMultipart() {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        Receipt receipt = client().listen().submit(TranscribeRequest.builder()
                .bytes("RIFFdata".getBytes(StandardCharsets.US_ASCII)).model(MODEL).language("ja")
                .hotwords(List.of("かいみょう", "a,b")).metadata(Map.of("call_id", "c-42"))
                .webhookUrl("https://example.com/done").tags(List.of("campaign-7", "env:prod")).build());
        assertEquals(new Receipt("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", "queued", 1), receipt);

        FakeApi.Recorded r = api.requests.get(0);
        assertEquals("tag=campaign-7&tag=env%3Aprod", r.query());
        assertTrue(r.header("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = r.bodyText();
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""));
        assertTrue(body.contains("RIFFdata"));
        assertTrue(body.contains("name=\"model\"\r\n\r\n" + MODEL + "\r\n"));
        assertTrue(body.contains("name=\"language\"\r\n\r\nja\r\n"));
        assertTrue(body.contains("name=\"hotwords\"\r\n\r\nかいみょう\r\n"));
        assertTrue(body.contains("name=\"hotwords\"\r\n\r\na,b\r\n"));
        assertTrue(body.contains("name=\"metadata\"\r\n\r\n{\"call_id\":\"c-42\"}\r\n"));
        assertTrue(body.contains("name=\"webhook_url\"\r\n\r\nhttps://example.com/done\r\n"));
        assertEquals("Token " + TestClients.KEY, r.header("Authorization"));
    }

    @Test
    void aPathGivesItsFileNameAndExactlyOneMibStillGoesDirect() throws Exception {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        Path file = tmp.resolve("meeting.mp3");
        Files.write(file, audio(MIB));
        client().listen().submit(TranscribeRequest.builder().file(file).model(MODEL).language("ja").build());
        assertTrue(api.requests.get(0).bodyText().contains("filename=\"meeting.mp3\""));
        assertTrue(api.requests("POST", "/v1/listen/uploads").isEmpty());
    }

    @Test
    void aLargeFileGoesThroughTheUploadFlow() {
        int size = MIB + 1;
        byte[] data = audio(size);
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));

        Receipt receipt = client().listen().submit(TranscribeRequest.builder().bytes(data).filename("call.wav")
                .model(MODEL).language("ja").hotwords(List.of("かいみょう")).metadata(Map.of("call_id", "c-42"))
                .webhookUrl("https://example.com/done").build());
        assertEquals("queued", receipt.status());

        JsonNode start = Json.tree(api.requests("POST", "/v1/listen/uploads").get(0).body());
        assertEquals(size, start.get("size").asInt());
        assertEquals("call.wav", start.get("filename").asText());

        FakeApi.Recorded put = api.requests("PUT", "/storage/up1").get(0);
        assertEquals("X-Goog-Signature=secret", put.query());
        assertEquals("0", put.header("x-goog-if-generation-match"));
        assertEquals(size + "," + size, put.header("x-goog-content-length-range"));
        assertEquals(String.valueOf(size), put.header("Content-Length"));
        assertFalse(put.hasHeader("Authorization"), "the API key never goes to storage");
        assertFalse(put.hasHeader("X-Trace-Id"));
        assertArrayEquals(data, put.body());

        FakeApi.Recorded submit = api.requests("POST", "/v1/listen").get(0);
        assertEquals("application/json", submit.header("Content-Type"));
        JsonNode body = Json.tree(submit.body());
        assertEquals("up1", body.get("upload_id").asText());
        assertEquals(MODEL, body.get("model").asText());
        assertEquals("ja", body.get("language").asText());
        assertEquals("かいみょう", body.get("hotwords").get(0).asText());
        assertEquals("c-42", body.get("metadata").get("call_id").asText());
        assertEquals("https://example.com/done", body.get("webhook_url").asText());
        assertFalse(body.has("url"));
    }

    @Test
    void uploadsFromAPathAndFromAStreamOfKnownSize() throws Exception {
        int size = 2 * MIB;
        byte[] data = audio(size);
        Path file = tmp.resolve("long.wav");
        Files.write(file, data);
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));

        client().listen().submit(TranscribeRequest.builder().file(file).model(MODEL).language("ja").build());
        InputStream in = new ByteArrayInputStream(data);
        client().listen().submit(TranscribeRequest.builder().stream(in, size).model(MODEL).language("ja").build());

        List<FakeApi.Recorded> puts = api.requests("PUT", "/storage/up1");
        assertEquals(2, puts.size());
        assertArrayEquals(data, puts.get(0).body());
        assertArrayEquals(data, puts.get(1).body());
        assertEquals("long.wav", Json.tree(api.requests("POST", "/v1/listen/uploads").get(0).body()).get("filename").asText());
    }

    @Test
    void theUploadBodyHasNoTimeoutBeyondConnecting() {
        int size = MIB + 10;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.delayed(700, FakeApi.respond(200, Map.of(), "")));
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).timeout(Duration.ofMillis(250)).build();
        assertEquals("queued", c.listen().submit(TranscribeRequest.builder().bytes(audio(size)).model(MODEL)
                .language("ja").build()).status());
    }

    @Test
    void submitIsGivenMoreThanTheClientTimeout() {
        api.on("POST", "/v1/listen", FakeApi.delayed(700, FakeApi.respond(202, Map.of(), RECEIPT)));
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).timeout(Duration.ofMillis(250)).build();
        assertEquals("queued", c.listen().submit(TranscribeRequest.builder().bytes(audio(10)).model(MODEL)
                .language("ja").build()).status());
    }

    @Test
    void submitRetriesUploadIncompleteOneSecondApart() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", error(409, "upload_incomplete", Map.of()),
                error(409, "upload_incomplete", Map.of()), FakeApi.respond(202, Map.of(), RECEIPT));
        client().listen().submit(TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), time.sleeps);
        assertEquals(3, api.requests("POST", "/v1/listen").size());
    }

    @Test
    void uploadIncompleteGivesUpAfterMaxRetries() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", error(409, "upload_incomplete", Map.of()));
        BadRequestException e = assertThrows(BadRequestException.class, () -> client().listen().submit(
                TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build()));
        assertEquals("upload_incomplete", e.code());
        assertEquals(3, api.requests("POST", "/v1/listen").size());
    }

    @Test
    void aRefusedPutIsAnUploadErrorThatNeverShowsTheUrlAndIsNotRetried() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(412, Map.of("Content-Type", "application/xml"),
                "<Error>PreconditionFailed</Error>"));
        UploadException e = assertThrows(UploadException.class, () -> client().listen().submit(
                TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build()));
        assertEquals(412, e.status());
        for (String s : List.of(e.getMessage(), e.toString())) {
            assertFalse(s.contains("secret"), s);
            assertFalse(s.contains("/storage/up1"), s);
            assertFalse(s.contains(TestClients.KEY), s);
        }
        assertEquals(1, api.requests("PUT", "/storage/up1").size());
        assertTrue(api.requests("POST", "/v1/listen").isEmpty());
    }

    @Test
    void aPutServerErrorIsNotRetriedEither() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(503, Map.of(), ""));
        assertThrows(UploadException.class, () -> client().listen().submit(
                TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build()));
        assertEquals(1, api.requests("PUT", "/storage/up1").size());
    }

    @Test
    void startingAnUploadIsRetriedLikeAGet() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", error(503, "service_unavailable", Map.of()),
                FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        client().listen().submit(TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build());
        assertEquals(2, api.requests("POST", "/v1/listen/uploads").size());
    }

    @Test
    void overOneHundredMibIsRefusedLocally() {
        InputStream never = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("must not be read");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> client().listen().submit(TranscribeRequest.builder()
                .stream(never, 104_857_601L).model(MODEL).language("ja").build()));
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void aUrlIsSubmittedAsJson() {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), R.get("receipt_without_position").toString()));
        Receipt r = client().listen().submit(TranscribeRequest.builder().url("https://storage.example/a.wav?sig=x")
                .model(MODEL).language("ja").hotwords(List.of("x")).build());
        assertNull(r.queuePosition());
        FakeApi.Recorded rec = api.requests.get(0);
        assertEquals("application/json", rec.header("Content-Type"));
        JsonNode body = Json.tree(rec.body());
        assertEquals("https://storage.example/a.wav?sig=x", body.get("url").asText());
        assertEquals("x", body.get("hotwords").get(0).asText());
        assertFalse(body.has("metadata"));
        assertFalse(body.has("upload_id"));
    }

    @Test
    void requiresExactlyOneSourceAndAModelAndLanguage() {
        assertThrows(IllegalArgumentException.class, () -> TranscribeRequest.builder().model(MODEL).language("ja").build());
        assertThrows(IllegalArgumentException.class, () -> TranscribeRequest.builder().bytes(new byte[1])
                .url("https://x.example/a.wav").model(MODEL).language("ja").build());
        assertThrows(IllegalArgumentException.class, () -> TranscribeRequest.builder().bytes(new byte[1]).language("ja").build());
        assertThrows(IllegalArgumentException.class, () -> TranscribeRequest.builder().bytes(new byte[1]).model(MODEL).build());
    }

    @Test
    void submitIsRetriedLikeAGetWithTheSameBodyAndKey() {
        api.on("POST", "/v1/listen", error(503, "engine_busy", Map.of()), error(502, "upstream_unavailable", Map.of()),
                FakeApi.respond(202, Map.of(), RECEIPT));
        client().listen().submit(TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja").build());
        assertEquals(3, api.requests.size());
        assertArrayEquals(api.requests.get(0).body(), api.requests.get(1).body(), "the same body is sent again");
        assertArrayEquals(api.requests.get(0).body(), api.requests.get(2).body(), "the same body is sent again");
        String key = api.requests.get(0).header("Idempotency-Key");
        assertNotNull(key);
        assertEquals(key, api.requests.get(1).header("Idempotency-Key"));
        assertEquals(key, api.requests.get(2).header("Idempotency-Key"));
    }

    @Test
    void submitIsRetriedOnAConnectionError() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        VerbexSpeech c = TestClients.builder("http://127.0.0.1:" + port, time).maxRetries(2).build();
        assertThrows(ApiConnectionException.class, () -> c.listen().submit(
                TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja").build()));
        assertEquals(2, time.sleeps.size(), "two retries after the first attempt");
    }

    @Test
    void everySubmitSendsAFreshUuidIdempotencyKey() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        client().listen().submit(TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja").build());
        client().listen().submit(TranscribeRequest.builder().url("https://storage.example/a.wav").model(MODEL)
                .language("ja").build());
        client().listen().submit(TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build());

        List<FakeApi.Recorded> submits = api.requests("POST", "/v1/listen");
        assertEquals(3, submits.size());
        assertTrue(submits.get(0).header("Content-Type").startsWith("multipart/form-data"));
        assertTrue(Json.tree(submits.get(1).body()).has("url"));
        assertTrue(Json.tree(submits.get(2).body()).has("upload_id"));
        Set<String> keys = new HashSet<>();
        for (FakeApi.Recorded r : submits) {
            String key = r.header("Idempotency-Key");
            assertNotNull(key, "every submit carries a key");
            assertEquals(key, UUID.fromString(key).toString(), "a UUID");
            keys.add(key);
        }
        assertEquals(3, keys.size(), "one key per submit call");
        assertFalse(api.requests("POST", "/v1/listen/uploads").get(0).hasHeader("Idempotency-Key"));
        assertFalse(api.requests("PUT", "/storage/up1").get(0).hasHeader("Idempotency-Key"));
    }

    @Test
    void theUploadFlowKeepsItsKeyThroughUploadIncompleteAndRequestInProgress() {
        int size = MIB + 1;
        api.on("POST", "/v1/listen/uploads", FakeApi.respond(201, Map.of(), uploadStarted(size)));
        api.on("PUT", "/storage/up1", FakeApi.respond(200, Map.of(), ""));
        api.on("POST", "/v1/listen", error(409, "upload_incomplete", Map.of()),
                error(409, "request_in_progress", Map.of()), FakeApi.respond(202, Map.of(), RECEIPT));
        client().listen().submit(TranscribeRequest.builder().bytes(audio(size)).model(MODEL).language("ja").build());
        List<FakeApi.Recorded> submits = api.requests("POST", "/v1/listen");
        assertEquals(3, submits.size());
        String key = submits.get(0).header("Idempotency-Key");
        assertNotNull(key);
        for (FakeApi.Recorded r : submits) {
            assertEquals(key, r.header("Idempotency-Key"));
        }
    }

    @Test
    void requestInProgressIsRetriedForAUrlSubmit() {
        api.on("POST", "/v1/listen", error(409, "request_in_progress", Map.of()), FakeApi.respond(202, Map.of(), RECEIPT));
        Receipt r = client().listen().submit(TranscribeRequest.builder().url("https://storage.example/a.wav")
                .model(MODEL).language("ja").idempotencyKey("order-77").build());
        assertEquals("queued", r.status());
        assertEquals(2, api.requests.size());
        assertEquals("order-77", api.requests.get(0).header("Idempotency-Key"));
        assertEquals("order-77", api.requests.get(1).header("Idempotency-Key"));
    }

    @Test
    void theCallersKeyIsSentVerbatim() {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        String key = "job:2026-10-14/batch#7~!" + "x".repeat(231);
        assertEquals(255, key.length());
        client().listen().submit(TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja")
                .idempotencyKey(key).build());
        assertEquals(key, api.requests.get(0).header("Idempotency-Key"));
    }

    @Test
    void aBadCallerKeyIsRefusedBeforeAnythingIsSent() {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        for (String bad : List.of("", "a b", "tab\tkey", "caf\u00e9", "x".repeat(256), "line\n")) {
            assertThrows(IllegalArgumentException.class, () -> client().listen().submit(TranscribeRequest.builder()
                    .bytes(audio(10)).model(MODEL).language("ja").idempotencyKey(bad).build()), bad);
        }
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void replayedIsReadFromTheIdempotentReplayedHeader() {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of("Idempotent-Replayed", "TRUE"), RECEIPT),
                FakeApi.respond(202, Map.of("Idempotent-Replayed", "false"), RECEIPT),
                FakeApi.respond(202, Map.of(), RECEIPT));
        TranscribeRequest req = TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja").build();
        assertTrue(client().listen().submit(req).replayed());
        assertFalse(client().listen().submit(req).replayed());
        Receipt plain = client().listen().submit(req);
        assertFalse(plain.replayed());
        assertEquals(new Receipt("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", "queued", 1), plain);
    }

    @Test
    void aReusedKeyIsABadRequestAndNotRetried() {
        api.on("POST", "/v1/listen", error(422, "idempotency_key_reused", Map.of()));
        BadRequestException e = assertThrows(BadRequestException.class, () -> client().listen().submit(
                TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja").idempotencyKey("k1").build()));
        assertEquals(422, e.status());
        assertEquals("idempotency_key_reused", e.code());
        assertEquals(1, api.requests.size());
    }

    @Test
    void transcribeSubmitsThenWaits() throws Exception {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        api.on("GET", "/v1/listen/9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", json(200, R.get("job_processing").toString()),
                json(200, R.get("job_completed").toString()));
        Path file = tmp.resolve("a.wav");
        Files.write(file, audio(100));
        Job job = client().listen().transcribe(file, MODEL, "ja");
        assertTrue(job.isCompleted());
        assertEquals(List.of(Duration.ofSeconds(2)), time.sleeps);
    }

    @Test
    void usesTheCallersTraceId() {
        api.on("POST", "/v1/listen", FakeApi.respond(202, Map.of(), RECEIPT));
        String trace = "6f1c2a3e-9b7d-4c1e-8a2f-0d5e6b7c8d9e";
        client().listen().submit(TranscribeRequest.builder().bytes(audio(10)).model(MODEL).language("ja")
                .traceId(trace).build());
        assertEquals(trace, api.requests.get(0).header("X-Trace-Id"));
    }

    @Test
    void requestToStringHidesTheAudioUrl() {
        TranscribeRequest r = TranscribeRequest.builder().url("https://storage.example/a.wav?sig=secret")
                .model(MODEL).language("ja").build();
        assertFalse(r.toString().contains("secret"));
        assertFalse(Arrays.toString(new Object[]{r}).contains("sig="));
    }
}

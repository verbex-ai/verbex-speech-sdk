package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.ApiConnectionException;
import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.ApiTimeoutException;
import ai.verbex.speech.sdk.error.RateLimitException;
import ai.verbex.speech.sdk.error.ServerException;
import ai.verbex.speech.sdk.error.VerbexException;
import ai.verbex.speech.sdk.listen.job.WaitTimeoutException;

import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.transport.ErrorMappingTest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.verbex.speech.sdk.FakeApi.error;
import static ai.verbex.speech.sdk.FakeApi.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpBehaviourTest {
    private static final JsonNode R = Conformance.load("responses.json");
    private static final String JOB = "9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b";

    private final FakeApi api = new FakeApi();
    private final TestClients.VirtualTime time = new TestClients.VirtualTime();

    @AfterEach
    void stop() {
        api.close();
    }

    private VerbexSpeech client() {
        return TestClients.builder(api.baseUrl(), time).build();
    }

    @Test
    void sendsTheKeyUserAgentAndAFreshTraceId() {
        api.on("GET", "/v1/listen/models", json(200, R.get("models").toString()));
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).userAgentSuffix("my-app/2").build();
        assertEquals(1, c.listen().models().size());
        c.listen().models();
        FakeApi.Recorded first = api.requests.get(0);
        assertEquals("Token " + TestClients.KEY, first.header("Authorization"));
        assertEquals("verbex-speech-java/0.1.0-SNAPSHOT my-app/2", first.header("User-Agent"));
        UUID.fromString(first.header("X-Trace-Id"));
        assertNotEquals(first.header("X-Trace-Id"), api.requests.get(1).header("X-Trace-Id"));
    }

    @Test
    void listsVoicesAndReadsAJob() {
        api.on("GET", "/v1/speak/voices", json(200, R.get("voices").toString()));
        api.on("GET", "/v1/listen/" + JOB, json(200, R.get("job_completed").toString()));
        assertEquals("maria_2", client().speak().voices().get(0).code());
        Job job = client().listen().get(JOB);
        assertTrue(job.isCompleted());
        assertEquals(2, job.segments().size());
    }

    @Test
    void refusesAJobIdThatIsNotAPathSegment() {
        assertThrows(IllegalArgumentException.class, () -> client().listen().get("../models"));
    }

    @TestFactory
    List<DynamicTest> conformanceErrorsOverHttp() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : Conformance.load("errors.json").get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("status") + " " + c.get("code").asText(), () -> {
                try (FakeApi server = new FakeApi()) {
                    server.on("GET", "/v1/listen/" + JOB,
                            FakeApi.respond(c.get("status").asInt(), ErrorMappingTest.headers(c), ErrorMappingTest.body(c)));
                    VerbexSpeech client = TestClients.builder(server.baseUrl(), time).maxRetries(0).build();
                    ApiException e = assertThrows(ApiException.class, () -> client.listen().get(JOB));
                    ErrorMappingTest.assertMatches(c, e);
                    assertFalse(e.getMessage().contains(TestClients.KEY));
                }
            }));
        }
        return tests;
    }

    @Test
    void retriesAGetOn503WithBackoffThenSucceeds() {
        api.on("GET", "/v1/listen/" + JOB, error(503, "service_unavailable", Map.of()),
                error(502, "upstream_unavailable", Map.of()), json(200, R.get("job_processing").toString()));
        assertEquals("processing", client().listen().get(JOB).status());
        assertEquals(3, api.requests.size());
        assertEquals(2, time.sleeps.size());
        assertTrue(time.sleeps.get(0).compareTo(Duration.ofMillis(500)) <= 0);
        assertTrue(time.sleeps.get(1).compareTo(Duration.ofMillis(1000)) <= 0);
    }

    @Test
    void honoursRetryAfter() {
        api.on("GET", "/v1/listen/" + JOB, error(429, "rate_limited", Map.of("Retry-After", "17")),
                json(200, R.get("job_processing").toString()));
        client().listen().get(JOB);
        assertEquals(List.of(Duration.ofSeconds(17)), time.sleeps);
    }

    @Test
    void doesNotWaitARetryAfterLongerThanSixtySeconds() {
        api.on("GET", "/v1/listen/" + JOB, error(429, "rate_limited", Map.of("Retry-After", "61")));
        RateLimitException e = assertThrows(RateLimitException.class, () -> client().listen().get(JOB));
        assertEquals(61L, e.retryAfter());
        assertEquals(1, api.requests.size());
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void givesUpAfterMaxRetries() {
        api.on("GET", "/v1/listen/" + JOB, error(503, "service_unavailable", Map.of()));
        assertThrows(ServerException.class, () -> client().listen().get(JOB));
        assertEquals(3, api.requests.size(), "1 + maxRetries (default 2)");
        api.requests.clear();
        VerbexSpeech none = TestClients.builder(api.baseUrl(), time).maxRetries(0).build();
        assertThrows(ServerException.class, () -> none.listen().get(JOB));
        assertEquals(1, api.requests.size());
    }

    @Test
    void doesNotRetryConcurrencyLimitsOrClientErrors() {
        api.on("GET", "/v1/listen/" + JOB, error(429, "concurrency_limit", Map.of()));
        assertThrows(RateLimitException.class, () -> client().listen().get(JOB));
        assertEquals(1, api.requests.size());
    }

    @Test
    void aRefusedConnectionIsAConnectionErrorAndIsRetriedForGets() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        VerbexSpeech c = TestClients.builder("http://127.0.0.1:" + port, time).build();
        ApiConnectionException e = assertThrows(ApiConnectionException.class, () -> c.listen().models());
        assertEquals(2, time.sleeps.size());
        assertFalse(e.getMessage().contains(TestClients.KEY));
    }

    @Test
    void noAnswerWithinTheTimeoutIsATimeoutError() {
        api.on("GET", "/v1/listen/models", FakeApi.delayed(1500, json(200, "{\"items\":[]}")));
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).timeout(Duration.ofMillis(200)).maxRetries(0).build();
        assertThrows(ApiTimeoutException.class, () -> c.listen().models());
    }

    @Test
    void waitPollsUntilTheJobIsFinished() {
        api.on("GET", "/v1/listen/" + JOB, json(200, R.get("job_processing").toString()),
                json(200, R.get("job_processing").toString()), json(200, R.get("job_completed").toString()));
        Job job = client().listen().waitFor(JOB);
        assertTrue(job.isCompleted());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(2)), time.sleeps);
    }

    @Test
    void waitReturnsFailedJobsWithoutRaising() {
        api.on("GET", "/v1/listen/" + JOB, json(200, R.get("job_failed").toString()));
        Job job = client().listen().waitFor(JOB);
        assertEquals("failed", job.status());
        assertEquals("invalid_audio", job.error().code());
    }

    @Test
    void waitNeverPollsFasterThanHalfASecond() {
        api.on("GET", "/v1/listen/" + JOB, json(200, R.get("job_processing").toString()),
                json(200, R.get("job_completed").toString()));
        client().listen().waitFor(JOB, Duration.ofMillis(10), null);
        assertEquals(List.of(Duration.ofMillis(500)), time.sleeps);
    }

    @Test
    void waitRidesOutA429ByWaitingRetryAfter() {
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).maxRetries(0).build();
        api.on("GET", "/v1/listen/" + JOB, error(429, "rate_limited", Map.of("Retry-After", "5")),
                json(200, R.get("job_completed").toString()));
        assertTrue(c.listen().waitFor(JOB).isCompleted());
        assertEquals(List.of(Duration.ofSeconds(5)), time.sleeps);
    }

    @Test
    void waitTimesOutCarryingTheLastJob() {
        api.on("GET", "/v1/listen/" + JOB, json(200, R.get("job_processing").toString()));
        WaitTimeoutException e = assertThrows(WaitTimeoutException.class,
                () -> client().listen().waitFor(JOB, Duration.ofSeconds(2), Duration.ofSeconds(5)));
        assertNotNull(e.lastJob());
        assertEquals("processing", e.lastJob().status());
        long slept = time.sleeps.stream().mapToLong(Duration::toMillis).sum();
        assertEquals(5000, slept);
    }

    @Test
    void waitTimeoutBeforeAnyJobCarriesNull() {
        VerbexSpeech c = TestClients.builder(api.baseUrl(), time).maxRetries(0).build();
        api.on("GET", "/v1/listen/" + JOB, error(429, "rate_limited", Map.of("Retry-After", "30")));
        WaitTimeoutException e = assertThrows(WaitTimeoutException.class,
                () -> c.listen().waitFor(JOB, Duration.ofSeconds(2), Duration.ofSeconds(10)));
        assertNull(e.lastJob());
    }

    @Test
    void errorTypesShareOneBase() {
        assertSame(VerbexException.class, ApiException.class.getSuperclass());
        assertTrue(RuntimeException.class.isAssignableFrom(VerbexException.class));
    }
}

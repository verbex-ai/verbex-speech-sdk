package ai.verbex.speech.sdk.listen.webhook;

import ai.verbex.speech.sdk.listen.job.Job;

import ai.verbex.speech.sdk.Conformance;



import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhooksTest {
    private static final JsonNode VECTORS = Conformance.load("webhook-vectors.json");
    private static final String API_KEY = VECTORS.get("api_key").asText();

    @Test
    void derivesTheSigningKeyFromTheApiKey() {
        assertEquals(VECTORS.get("signing_key").asText(), Webhooks.signingKey(API_KEY));
    }

    @TestFactory
    List<DynamicTest> conformanceVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : VECTORS.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                byte[] body = c.get("body_utf8").asText().getBytes(StandardCharsets.UTF_8);
                boolean ok = Webhooks.verify(API_KEY, body, c.get("timestamp").asText(),
                        c.get("signature").asText(), Duration.ofSeconds(300),
                        Instant.ofEpochSecond(c.get("now").asLong()));
                assertEquals(c.get("valid").asBoolean(), ok);
            }));
        }
        return tests;
    }

    @Test
    void neverThrowsForBadInput() {
        assertFalse(Webhooks.verify(API_KEY, null, null, null));
        assertFalse(Webhooks.verify(null, new byte[0], "1", "v1=00"));
        assertFalse(Webhooks.verify(API_KEY, new byte[0], "99999999999999999999999", "v1=00"));
    }

    @Test
    void findsHeadersCaseInsensitivelyAndParsesTheJob() {
        JsonNode valid = VECTORS.get("cases").get(0);
        byte[] body = valid.get("body_utf8").asText().getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> headers = Map.of(
                "x-verbex-timestamp", List.of(valid.get("timestamp").asText()),
                "X-VERBEX-SIGNATURE", List.of(valid.get("signature").asText()));
        Instant now = Instant.ofEpochSecond(valid.get("now").asLong());

        assertTrue(Webhooks.verify(API_KEY, body, headers, Duration.ofSeconds(300), now));
        Job job = Webhooks.parse(API_KEY, body, headers, Duration.ofSeconds(300), now);
        assertNotNull(job);
        assertEquals("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", job.jobId());
        assertTrue(job.isCompleted());
        assertEquals("c-42", job.metadata().get("call_id"));
        assertEquals(1, job.segments().size());

        Map<String, String> single = Map.of(
                "X-Verbex-Timestamp", valid.get("timestamp").asText(),
                "x-verbex-signature", valid.get("signature").asText());
        assertTrue(Webhooks.verify(API_KEY, body, single, Duration.ofSeconds(300), now));
    }

    @Test
    void parseRaisesOnABadSignature() {
        JsonNode valid = VECTORS.get("cases").get(0);
        byte[] body = valid.get("body_utf8").asText().getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                "X-Verbex-Timestamp", valid.get("timestamp").asText(),
                "X-Verbex-Signature", "v1=" + "0".repeat(64));
        WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
                () -> Webhooks.parse(API_KEY, body, headers, Duration.ofSeconds(300),
                        Instant.ofEpochSecond(valid.get("now").asLong())));
        assertFalse(e.getMessage().contains(API_KEY));
    }
}

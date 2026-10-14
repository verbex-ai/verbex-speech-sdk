package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.Conformance;

import ai.verbex.speech.sdk.error.ApiConnectionException;
import ai.verbex.speech.sdk.error.ApiTimeoutException;
import ai.verbex.speech.sdk.error.VerbexException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** retry-decisions.json, plus the backoff rules of spec §8. */
class RetryDecisionsTest {

    static VerbexException outcome(JsonNode c) {
        JsonNode o = c.get("outcome");
        if (o.isTextual()) {
            return "timeout".equals(o.asText()) ? new ApiTimeoutException("t") : new ApiConnectionException("c");
        }
        String body = "{\"error\":{\"code\":\"" + c.get("code").asText() + "\",\"message\":\"m\"}}";
        return Errors.fromResponse(o.asInt(), n -> null, body.getBytes());
    }

    @TestFactory
    List<DynamicTest> conformanceDecisions() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : Conformance.load("retry-decisions.json").get("cases")) {
            String name = c.get("call").asText() + " " + c.get("outcome").asText() + " " + c.get("code").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                CallKind kind = CallKind.valueOf(c.get("call").asText().toUpperCase(Locale.ROOT));
                assertEquals(c.get("retry").asBoolean(), RetryPolicy.shouldRetry(kind, outcome(c)));
            }));
        }
        return tests;
    }

    @Test
    void backoffIsFullJitterFromHalfASecondCappedAtEight() {
        for (int attempt = 0; attempt < 10; attempt++) {
            Duration cap = Duration.ofMillis(Math.min(8000L, 500L << attempt));
            for (int i = 0; i < 50; i++) {
                Duration d = RetryPolicy.delay(attempt, null);
                assertTrue(!d.isNegative() && d.compareTo(cap) <= 0, d + " within " + cap);
            }
        }
    }

    @Test
    void retryAfterIsHonouredUpToSixtySeconds() {
        assertEquals(Duration.ofSeconds(17), RetryPolicy.delay(0, 17L));
        assertEquals(Duration.ofSeconds(60), RetryPolicy.delay(3, 60L));
        assertNull(RetryPolicy.delay(0, 61L), "a longer wait is not taken");
    }
}

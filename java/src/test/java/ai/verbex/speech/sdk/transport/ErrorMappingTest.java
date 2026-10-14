package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.Conformance;

import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.BadRequestException;
import ai.verbex.speech.sdk.error.FieldError;
import ai.verbex.speech.sdk.error.ServerException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** errors.json: each response maps to the spec's error type (Error suffix becomes Exception). */
public class ErrorMappingTest {

    static Class<?> javaType(String specName) throws ClassNotFoundException {
        String simple = specName.endsWith("Error")
                ? specName.substring(0, specName.length() - "Error".length()) + "Exception" : specName;
        return Class.forName("ai.verbex.speech.sdk.error." + simple);
    }

    public static byte[] body(JsonNode c) {
        if (c.has("body_text")) {
            return c.get("body_text").asText().getBytes(StandardCharsets.UTF_8);
        }
        return c.get("body").toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Map<String, String> headers(JsonNode c) {
        Map<String, String> h = new LinkedHashMap<>();
        c.get("headers").fields().forEachRemaining(e -> h.put(e.getKey(), e.getValue().asText()));
        return h;
    }

    public static void assertMatches(JsonNode c, ApiException e) throws ClassNotFoundException {
        assertSame(javaType(c.get("type").asText()), e.getClass());
        assertEquals(c.get("status").asInt(), e.status());
        assertEquals(c.get("code").asText(), e.code());
        assertEquals(c.get("request_id").asText(), e.requestId());
        List<FieldError> expected = new ArrayList<>();
        for (JsonNode f : c.get("fields")) {
            expected.add(new FieldError(f.get("name").asText(), f.get("reason").asText()));
        }
        assertEquals(expected, e.fields());
        if (c.get("retry_after").isNull()) {
            assertNull(e.retryAfter());
        } else {
            assertEquals(c.get("retry_after").asLong(), e.retryAfter());
        }
    }

    @TestFactory
    List<DynamicTest> conformanceErrors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : Conformance.load("errors.json").get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("status") + " " + c.get("code").asText(), () -> {
                ApiException e = Errors.fromResponse(c.get("status").asInt(),
                        name -> headers(c).entrySet().stream()
                                .filter(h -> h.getKey().equalsIgnoreCase(name))
                                .map(Map.Entry::getValue).findFirst().orElse(null),
                        body(c));
                assertMatches(c, e);
            }));
        }
        return tests;
    }

    @Test
    void otherStatusesMapByClass() {
        assertInstanceOf(BadRequestException.class, Errors.fromResponse(405, n -> null, new byte[0]));
        assertInstanceOf(BadRequestException.class, Errors.fromResponse(415, n -> null, new byte[0]));
        assertInstanceOf(ServerException.class, Errors.fromResponse(500, n -> null, new byte[0]));
        assertSame(ApiException.class, Errors.fromResponse(418, n -> null, new byte[0]).getClass());
        assertEquals("http_418", Errors.fromResponse(418, n -> null, "{}".getBytes()).code());
    }
}

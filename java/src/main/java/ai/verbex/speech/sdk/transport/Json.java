package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.error.VerbexException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Shared machinery of the SDK's {@code listen} and {@code speak} packages. Public only because Java
 * cannot share a type between packages otherwise: <b>not part of the API</b>, and it may change in any
 * release. Use {@link VerbexSpeech}.
 */
public final class Json {
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    public static <T> T read(byte[] body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new VerbexException("The response could not be parsed as " + type.getSimpleName());
        }
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new VerbexException("The response could not be parsed as " + type.getSimpleName());
        }
    }

    public static JsonNode tree(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonNode tree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value cannot be written as JSON: " + e.getOriginalMessage());
        }
    }
}

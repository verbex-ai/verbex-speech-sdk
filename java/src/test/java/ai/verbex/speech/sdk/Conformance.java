package ai.verbex.speech.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the shared fixtures from {@code ../conformance/} (system property {@code verbex.conformance.dir}). */
public final class Conformance {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Conformance() {
    }

    static Path dir() {
        String dir = System.getProperty("verbex.conformance.dir", "../conformance");
        return Path.of(dir);
    }

    public static JsonNode load(String name) {
        Path file = dir().resolve(name);
        try {
            return MAPPER.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read conformance fixture " + file.toAbsolutePath(), e);
        }
    }
}

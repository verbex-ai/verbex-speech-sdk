package ai.verbex.speech.sdk.speak.voice;


import ai.verbex.speech.sdk.Conformance;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** conformance/responses.json, the text-to-speech answers, parsed into this package's types. */
class VoicesTest {
    private static final JsonNode R = Conformance.load("responses.json");

    @Test
    void voices() {
        List<Voice> voices = Voices.parse(R.get("voices").toString().getBytes());
        assertEquals(2, voices.size());
        assertEquals(new Voice("verbex_v1_bn", "Verbex TTS v1 · Bengali", "maria_2", "Maria", "bn", "female",
                "warm", "https://cdn.example/maria.wav"), voices.get(0));
        Voice aoi = voices.get(1);
        assertEquals("Aoi", aoi.code());
        assertNull(aoi.displayName());
        assertNull(aoi.gender());
        assertNull(aoi.style());
        assertNull(aoi.sampleUrl());
    }
}

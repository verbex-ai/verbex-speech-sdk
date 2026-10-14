package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.ConfigurationException;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientConfigTest {
    private static final String KEY = TestClients.KEY;

    @Test
    void aMissingKeyIsAConfigurationErrorAtConstruction() {
        assertThrows(ConfigurationException.class,
                () -> VerbexSpeech.builder().baseUrl("https://api.example").env(n -> null).build());
    }

    @Test
    void aKeyMustStartWithVbx() {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> VerbexSpeech.builder().apiKey("sk_live_secret123").baseUrl("https://api.example")
                        .env(n -> null).build());
        assertFalse(e.getMessage().contains("sk_live_secret123"));
    }

    @Test
    void aMissingOrInvalidBaseUrlIsAConfigurationError() {
        assertThrows(ConfigurationException.class,
                () -> VerbexSpeech.builder().apiKey(KEY).env(n -> null).build());
        assertThrows(ConfigurationException.class,
                () -> VerbexSpeech.builder().apiKey(KEY).baseUrl("ftp://api.example").env(n -> null).build());
        assertThrows(ConfigurationException.class,
                () -> VerbexSpeech.builder().apiKey(KEY).baseUrl("not a url").env(n -> null).build());
    }

    @Test
    void fallsBackToTheEnvironment() {
        Map<String, String> env = Map.of("VERBEX_API_KEY", KEY, "VERBEX_BASE_URL", "https://api.example/");
        VerbexSpeech client = VerbexSpeech.builder().env(env::get).build();
        assertEquals("https://api.example", client.baseUrl());
    }

    @Test
    void ignoresATrailingSlashAndDerivesTheWebSocketScheme() {
        VerbexSpeech c = VerbexSpeech.builder().apiKey(KEY).baseUrl("https://api.example/").env(n -> null).build();
        assertEquals("https://api.example", c.baseUrl());
        assertEquals("wss://api.example/v1/listen/stream", c.transport().webSocketUri("/v1/listen/stream", null).toString());
        VerbexSpeech local = VerbexSpeech.builder().apiKey(KEY).baseUrl("http://127.0.0.1:1").env(n -> null).build();
        assertEquals("ws://127.0.0.1:1/v1/speak/stream", local.transport().webSocketUri("/v1/speak/stream", null).toString());
    }

    @Test
    void negativeRetriesAreRefused() {
        assertThrows(ConfigurationException.class, () -> VerbexSpeech.builder().apiKey(KEY)
                .baseUrl("https://api.example").env(n -> null).maxRetries(-1).build());
    }

    @Test
    void toStringNeverShowsTheKey() {
        VerbexSpeech c = VerbexSpeech.builder().apiKey(KEY).baseUrl("https://api.example").env(n -> null).build();
        assertFalse(c.toString().contains(KEY));
        assertFalse(c.toString().contains("abcdefghij"));
    }
}

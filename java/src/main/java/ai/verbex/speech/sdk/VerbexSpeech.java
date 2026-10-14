package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.error.ConfigurationException;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.Sleeper;

import ai.verbex.speech.sdk.listen.Listen;
import ai.verbex.speech.sdk.speak.Speak;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The Verbex Speech API client. Thread-safe; build one and share it.
 *
 * <pre>{@code
 * VerbexSpeech client = VerbexSpeech.builder()          // or env VERBEX_API_KEY / VERBEX_BASE_URL
 *         .apiKey("vbx_...").baseUrl("https://speech-api-stage.verbex.ai").build();
 * Job job = client.listen().transcribe(TranscribeRequest.file(Path.of("call.wav"), "verbex_jp_stt_x1", "ja"));
 * }</pre>
 */
public final class VerbexSpeech {
    /** The SDK version, from the build. */
    public static final String VERSION = loadVersion();
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_MAX_RETRIES = 2;

    private final String baseUrl;
    private final HttpTransport transport;
    private final Listen listen;
    private final Speak speak;

    private VerbexSpeech(Builder b, String apiKey, String baseUrl) {
        this.baseUrl = baseUrl;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(b.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String userAgent = "verbex-speech-java/" + VERSION
                + (b.userAgentSuffix == null || b.userAgentSuffix.isBlank() ? "" : " " + b.userAgentSuffix.trim());
        this.transport = new HttpTransport(http, apiKey, baseUrl, userAgent, b.timeout, b.maxRetries, b.sleeper,
                b.ticker);
        this.listen = new Listen(transport);
        this.speak = new Speak(transport);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The base URL without a trailing slash. */
    public String baseUrl() {
        return baseUrl;
    }

    /** Speech-to-text: models, file jobs, live sessions. */
    public Listen listen() {
        return listen;
    }

    /** Text-to-speech: voices, one utterance, many utterances. */
    public Speak speak() {
        return speak;
    }

    HttpTransport transport() {
        return transport;
    }

    @Override
    public String toString() {
        return "VerbexSpeech[baseUrl=" + baseUrl + ", apiKey=<redacted>]";
    }

    private static String loadVersion() {
        try (InputStream in = VerbexSpeech.class.getResourceAsStream("version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            String v = p.getProperty("version");
            return v == null || v.startsWith("${") ? "unknown" : v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Options of spec §3. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private String userAgentSuffix;
        private Function<String, String> env = System::getenv;
        private Sleeper sleeper = Sleeper.REAL;
        private LongSupplier ticker = System::nanoTime;

        private Builder() {
        }

        /** The API key ({@code vbx_...}); defaults to env {@code VERBEX_API_KEY}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The API's base URL; defaults to env {@code VERBEX_BASE_URL}. No built-in default. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Per HTTP call (default 30 s), and the connect timeout; submit and speak have longer defaults. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Automatic retries per spec §8 (default 2); 0 turns them off. */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /** Appended to {@code verbex-speech-java/<version>} in {@code User-Agent}. */
        public Builder userAgentSuffix(String suffix) {
            this.userAgentSuffix = suffix;
            return this;
        }

        Builder env(Function<String, String> env) {
            this.env = env;
            return this;
        }

        Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        Builder ticker(LongSupplier ticker) {
            this.ticker = ticker;
            return this;
        }

        /** @throws ConfigurationException when the key or base URL is missing or invalid */
        public VerbexSpeech build() {
            String key = apiKey != null ? apiKey : env.apply("VERBEX_API_KEY");
            if (key == null || key.isBlank()) {
                throw new ConfigurationException("No API key: pass apiKey(...) or set VERBEX_API_KEY.");
            }
            key = key.trim();
            if (!key.startsWith("vbx_")) {
                throw new ConfigurationException("The API key is not valid: Verbex API keys start with vbx_.");
            }
            String url = baseUrl != null ? baseUrl : env.apply("VERBEX_BASE_URL");
            if (url == null || url.isBlank()) {
                throw new ConfigurationException("No base URL: pass baseUrl(...) or set VERBEX_BASE_URL.");
            }
            url = url.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            try {
                URI uri = new URI(url);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new ConfigurationException("The base URL must be an http(s) URL with a host, such as https://speech-api-stage.verbex.ai.");
                }
            } catch (URISyntaxException e) {
                throw new ConfigurationException("The base URL is not a valid URL.");
            }
            if (timeout.isNegative() || timeout.isZero()) {
                throw new ConfigurationException("timeout must be positive.");
            }
            if (maxRetries < 0) {
                throw new ConfigurationException("maxRetries must be 0 or more.");
            }
            return new VerbexSpeech(this, key, url);
        }
    }
}

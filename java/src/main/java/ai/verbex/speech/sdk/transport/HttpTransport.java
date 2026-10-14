package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.error.ApiConnectionException;
import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.ApiTimeoutException;
import ai.verbex.speech.sdk.error.VerbexException;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Shared machinery of the SDK's {@code listen} and {@code speak} packages. Public only because Java
 * cannot share a type between packages otherwise: <b>not part of the API</b>, and it may change in any
 * release. Use {@link VerbexSpeech}.
 */
public final class HttpTransport {
    final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    final String userAgent;
    final Duration timeout;
    final int maxRetries;
    final Sleeper sleeper;
    final LongSupplier ticker;

    public HttpTransport(HttpClient http, String apiKey, String baseUrl, String userAgent, Duration timeout, int maxRetries,
                  Sleeper sleeper, LongSupplier ticker) {
        this.http = http;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.sleeper = sleeper;
        this.ticker = ticker;
    }

    public HttpClient httpClient() {
        return http;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public String authorization() {
        return "Token " + apiKey;
    }

    /** A request to the API with Authorization, User-Agent and X-Trace-Id set. */
    public HttpRequest.Builder request(String pathAndQuery, String traceId, Duration callTimeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(callTimeout)
                .header("Authorization", authorization())
                .header("User-Agent", userAgent)
                .header("X-Trace-Id", traceId);
    }

    /** GET returning the body as bytes. */
    public byte[] get(String pathAndQuery) {
        return send(CallKind.GET, null, trace -> request(pathAndQuery, trace, timeout).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    /** POST of a JSON body returning the body as bytes. */
    public byte[] postJson(CallKind kind, String pathAndQuery, byte[] json, Duration callTimeout, String traceId) {
        return send(kind, traceId, trace -> request(pathAndQuery, trace, callTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    /**
     * Sends with retries. A 2xx response is returned; anything else is raised as its §7 type.
     *
     * @param callerTraceId the caller's own X-Trace-Id, or null for a fresh UUID per attempt
     */
    public <T> HttpResponse<T> send(CallKind kind, String callerTraceId, Function<String, HttpRequest> factory,
                             HttpResponse.BodyHandler<T> handler) {
        for (int attempt = 0; ; attempt++) {
            VerbexException failure;
            try {
                String trace = callerTraceId != null ? callerTraceId : UUID.randomUUID().toString();
                HttpResponse<T> response = http.send(factory.apply(trace), handler);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response;
                }
                failure = Errors.fromResponse(status, name -> response.headers().firstValue(name).orElse(null),
                        bodyBytes(response.body()));
            } catch (HttpTimeoutException e) {
                failure = new ApiTimeoutException("No response from the API within the timeout.");
            } catch (IOException e) {
                failure = new ApiConnectionException("Could not reach the API: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VerbexException("Interrupted while calling the API.");
            }
            if (attempt >= maxRetries || !RetryPolicy.shouldRetry(kind, failure)) {
                throw failure;
            }
            Long retryAfter = failure instanceof ApiException api ? api.retryAfter() : null;
            Duration delay = RetryPolicy.delay(attempt, retryAfter);
            if (delay == null) {
                throw failure;
            }
            sleep(delay);
        }
    }

    public void sleep(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerbexException("Interrupted while waiting to retry.");
        }
    }

    public long nanoTime() {
        return ticker.getAsLong();
    }

    private static byte[] bodyBytes(Object body) {
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof InputStream in) {
            try (in) {
                return in.readNBytes(64 * 1024);
            } catch (IOException e) {
                return new byte[0];
            }
        }
        return new byte[0];
    }

    /** The same host as {@link #baseUrl()} with {@code ws}/{@code wss}; query values are percent-encoded. */
    public URI webSocketUri(String path, List<String[]> query) {
        String ws = baseUrl.startsWith("https:") ? "wss:" + baseUrl.substring("https:".length())
                : "ws:" + baseUrl.substring("http:".length());
        return URI.create(ws + path + queryString(query));
    }

    public static String queryString(List<String[]> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String[] kv : query) {
            sb.append(sb.length() == 0 ? '?' : '&').append(encode(kv[0])).append('=').append(encode(kv[1]));
        }
        return sb.toString();
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }


    /** {@code tag} query parameters, one per tag. */
    public static List<String[]> tagQuery(List<String> tags) {
        List<String[]> q = new java.util.ArrayList<>();
        if (tags != null) {
            for (String t : tags) {
                q.add(new String[]{"tag", t});
            }
        }
        return q;
    }
}

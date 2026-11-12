package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.VerbexSpeech;

import java.net.http.HttpHeaders;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code Idempotency-Key} a submit or a speak sends, and the {@code Idempotent-Replayed} answer.
 * Public only because Java cannot share a type between packages otherwise: <b>not part of the API</b>,
 * and it may change in any release. Use {@link VerbexSpeech}.
 */
public final class Idempotency {
    public static final String HEADER = "Idempotency-Key";

    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private Idempotency() {
    }

    /** @throws IllegalArgumentException unless a given key is 1 to 255 visible ASCII characters */
    public static void check(String key) {
        if (key != null && !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("idempotencyKey must be 1 to 255 visible ASCII characters");
        }
    }

    /** The caller's key, else a random UUID: made once per call and sent with each of its retries. */
    public static String keyFor(String callersKey) {
        return callersKey != null ? callersKey : UUID.randomUUID().toString();
    }

    /** True when the answer came from an earlier request with the same key ({@code Idempotent-Replayed: true}). */
    public static boolean replayed(HttpHeaders headers) {
        return headers.firstValue("Idempotent-Replayed").map(v -> v.trim().equalsIgnoreCase("true")).orElse(false);
    }
}

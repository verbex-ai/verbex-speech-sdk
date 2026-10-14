package ai.verbex.speech.sdk.listen.webhook;

import ai.verbex.speech.sdk.listen.job.Job;

import ai.verbex.speech.sdk.transport.Json;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Webhook signature verification (spec §4.5). No HTTP server: hand in the exact raw body and the
 * request headers ({@code X-Verbex-Timestamp}, {@code X-Verbex-Signature}, found case-insensitively).
 */
public final class Webhooks {
    /** Header carrying the unix timestamp. */
    public static final String TIMESTAMP_HEADER = "X-Verbex-Timestamp";
    /** Header carrying {@code v1=<hex>}. */
    public static final String SIGNATURE_HEADER = "X-Verbex-Signature";
    /** Default tolerance between the timestamp and now. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,18}");

    private Webhooks() {
    }

    static String signingKey(String apiKey) {
        return hmacHex(apiKey.getBytes(StandardCharsets.UTF_8),
                "verbex-webhook-v1".getBytes(StandardCharsets.UTF_8));
    }

    /** Verifies with the default tolerance (300 s) against the current time. */
    public static boolean verify(String apiKey, byte[] rawBody, String timestamp, String signature) {
        return verify(apiKey, rawBody, timestamp, signature, DEFAULT_TOLERANCE, Instant.now());
    }

    /**
     * True when {@code signature} is {@code v1=<hex HMAC>} over {@code timestamp + "." + rawBody} and the
     * timestamp is within {@code tolerance} of {@code now}. Never throws for bad input.
     */
    public static boolean verify(String apiKey, byte[] rawBody, String timestamp, String signature,
                                 Duration tolerance, Instant now) {
        try {
            if (apiKey == null || rawBody == null || timestamp == null || signature == null
                    || tolerance == null || now == null) {
                return false;
            }
            if (!signature.startsWith("v1=") || !INTEGER.matcher(timestamp).matches()) {
                return false;
            }
            long ts = Long.parseLong(timestamp);
            if (Math.abs(now.getEpochSecond() - ts) > tolerance.getSeconds()) {
                return false;
            }
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] message = new byte[prefix.length + rawBody.length];
            System.arraycopy(prefix, 0, message, 0, prefix.length);
            System.arraycopy(rawBody, 0, message, prefix.length, rawBody.length);
            String expected = hmacHex(signingKey(apiKey).getBytes(StandardCharsets.UTF_8), message);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.substring(3).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Verifies with the default tolerance against the current time; header values may be strings or lists. */
    public static boolean verify(String apiKey, byte[] rawBody, Map<String, ?> headers) {
        return verify(apiKey, rawBody, headers, DEFAULT_TOLERANCE, Instant.now());
    }

    /** As {@link #verify(String, byte[], String, String, Duration, Instant)}, reading the two headers from a map. */
    public static boolean verify(String apiKey, byte[] rawBody, Map<String, ?> headers, Duration tolerance,
                                 Instant now) {
        return verify(apiKey, rawBody, header(headers, TIMESTAMP_HEADER), header(headers, SIGNATURE_HEADER),
                tolerance, now);
    }

    /** Verifies, then parses the body as a {@link Job}; throws {@link WebhookVerificationException} if it does not verify. */
    public static Job parse(String apiKey, byte[] rawBody, Map<String, ?> headers) {
        return parse(apiKey, rawBody, headers, DEFAULT_TOLERANCE, Instant.now());
    }

    /** As {@link #parse(String, byte[], Map)} with an explicit tolerance and clock. */
    public static Job parse(String apiKey, byte[] rawBody, Map<String, ?> headers, Duration tolerance,
                            Instant now) {
        if (!verify(apiKey, rawBody, headers, tolerance, now)) {
            throw new WebhookVerificationException("The webhook signature did not verify.");
        }
        return Json.read(rawBody, Job.class);
    }

    private static String header(Map<String, ?> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                Object v = e.getValue();
                if (v instanceof Collection<?> c) {
                    return c.isEmpty() ? null : String.valueOf(c.iterator().next());
                }
                return v == null ? null : v.toString();
            }
        }
        return null;
    }

    private static String hmacHex(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

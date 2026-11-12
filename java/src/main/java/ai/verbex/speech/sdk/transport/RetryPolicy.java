package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.error.ApiConnectionException;
import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.ApiTimeoutException;
import ai.verbex.speech.sdk.error.VerbexException;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Spec §8: which failures are retried, and how long to wait. */
final class RetryPolicy {
    static final Duration BASE = Duration.ofMillis(500);
    static final Duration CAP = Duration.ofSeconds(8);
    static final long MAX_RETRY_AFTER_SECONDS = 60;

    private static final Set<String> RATE_LIMITED = Set.of("rate_limited", "workspace_rate_limited");

    private RetryPolicy() {
    }

    static boolean shouldRetry(CallKind kind, VerbexException error) {
        if (error instanceof ApiConnectionException || error instanceof ApiTimeoutException) {
            return true;
        }
        if (!(error instanceof ApiException api)) {
            return false;
        }
        int status = api.status();
        String code = api.code();
        if (status == 429) {
            return RATE_LIMITED.contains(code);
        }
        if (status == 409) {
            // A submit or a speak carries its Idempotency-Key, so waiting out the first request is safe.
            return (kind == CallKind.SUBMIT || kind == CallKind.SPEAK) && "request_in_progress".equals(code);
        }
        return status == 502 || status == 503 || status == 504;
    }

    /**
     * The wait before retry number {@code attempt + 1}: {@code Retry-After} when given (or
     * {@code null} when it exceeds 60 s, meaning "do not retry"), else full jitter over
     * {@code min(8 s, 0.5 s * 2^attempt)}.
     */
    static Duration delay(int attempt, Long retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            return retryAfterSeconds > MAX_RETRY_AFTER_SECONDS ? null : Duration.ofSeconds(retryAfterSeconds);
        }
        long ceiling = Math.min(CAP.toMillis(), BASE.toMillis() << Math.min(attempt, 20));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
    }
}

package ai.verbex.speech.sdk.transport;

import java.time.Duration;

/** Waits between retries and polls; replaced by a virtual clock in tests. Not part of the API. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = d -> Thread.sleep(d.toMillis(), (int) (d.toNanos() % 1_000_000));
}

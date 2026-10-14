package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.transport.Sleeper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Builds clients for tests with a virtual clock: sleeping advances time instantly. */
final class TestClients {
    static final String KEY = "vbx_test0000_abcdefghijklmnopqrstuvwxyz0123456789";

    static final class VirtualTime implements Sleeper {
        final List<Duration> sleeps = new CopyOnWriteArrayList<>();
        volatile long nanos = 0;

        @Override
        public void sleep(Duration d) {
            sleeps.add(d);
            nanos += d.toNanos();
        }

        long now() {
            return nanos;
        }
    }

    private TestClients() {
    }

    static VerbexSpeech.Builder builder(String baseUrl, VirtualTime time) {
        return VerbexSpeech.builder().apiKey(KEY).baseUrl(baseUrl).env(n -> null)
                .sleeper(time).ticker(time::now);
    }
}

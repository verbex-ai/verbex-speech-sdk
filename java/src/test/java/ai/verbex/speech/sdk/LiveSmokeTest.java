package ai.verbex.speech.sdk;

import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.listen.job.Segment;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.SpeakRequest;
import ai.verbex.speech.sdk.speak.voice.Voice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Spec §9 smoke test against a real API. Skipped unless VERBEX_API_KEY and VERBEX_BASE_URL are set.
 * Never run in CI against production.
 */
class LiveSmokeTest {
    private static final String STT_MODEL = "verbex_jp_stt_x1";

    @Test
    void smoke() throws Exception {
        assumeTrue(System.getenv("VERBEX_API_KEY") != null && System.getenv("VERBEX_BASE_URL") != null,
                "VERBEX_API_KEY and VERBEX_BASE_URL are not set");
        VerbexSpeech client = VerbexSpeech.builder().build();

        assertFalse(client.listen().models().isEmpty());
        List<Voice> voices = client.speak().voices();
        assertFalse(voices.isEmpty());

        Path samples = Path.of(System.getProperty("verbex.sample.dir", "../../documents/stt-engine-documents"));
        Job job = client.listen().transcribe(samples.resolve("sample-ja-16k-mono.wav"), STT_MODEL, "ja");
        assertEquals("completed", job.status());
        assertFalse(job.text() == null || job.text().isBlank());

        List<Segment> live = client.listen().transcribeStream(paced(samples.resolve("sample-ja-16k-mono.raw")),
                STT_MODEL, "ja", null, null);
        assertFalse(live.isEmpty());

        Voice voice = voices.stream().filter(v -> "ja".equals(v.language())).findFirst().orElse(voices.get(0));
        SpeakAudio audio = client.speak().synthesize(SpeakRequest.builder().model(voice.model()).voice(voice.code())
                .text("ja".equals(voice.language()) ? "こんにちは。" : "Hello.").build());
        assertTrue(audio.audio().length > 0);
    }

    /** 0.2 s chunks of 16 kHz s16le mono, released at real time. */
    private static Iterable<byte[]> paced(Path raw) throws IOException {
        byte[] pcm = Files.readAllBytes(raw);
        int chunk = 6400;
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < pcm.length; i += chunk) {
            chunks.add(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + chunk)));
        }
        return () -> new java.util.Iterator<>() {
            private int next = 0;
            private final long start = System.nanoTime();

            @Override
            public boolean hasNext() {
                return next < chunks.size();
            }

            @Override
            public byte[] next() {
                long due = start + next * 200_000_000L;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return chunks.get(next++);
            }
        };
    }
}

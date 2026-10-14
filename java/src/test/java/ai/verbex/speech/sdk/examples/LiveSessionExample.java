package ai.verbex.speech.sdk.examples;

import ai.verbex.speech.sdk.listen.stream.ListenEvent;
import ai.verbex.speech.sdk.listen.stream.ListenSession;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.VerbexSpeech;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Live transcription of a raw PCM file (16 kHz, mono, s16le), sent at real time as a microphone would.
 * Usage: LiveSessionExample &lt;file.raw&gt; [model] [language]. Convert a WAV first:
 * {@code ffmpeg -i in.wav -f s16le -ac 1 -ar 16000 out.raw}.
 */
public final class LiveSessionExample {
    private static final int CHUNK_BYTES = 6400; // 0.2 s of 16 kHz s16le mono

    private LiveSessionExample() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String model = args.length > 1 ? args[1] : "verbex_jp_stt_x1";
        String language = args.length > 2 ? args[2] : "ja";
        VerbexSpeech client = VerbexSpeech.builder().build();

        try (ListenSession session = client.listen().stream(model, language);
             InputStream in = Files.newInputStream(file)) {
            System.out.println("session " + session.sessionId());

            // Read events on their own thread while this one sends audio.
            Thread reader = new Thread(() -> {
                try {
                    ListenEvent event;
                    while ((event = session.nextEvent()) != null) {
                        if (event instanceof ListenEvent.SegmentEvent s) {
                            System.out.printf("[%7.2f - %7.2f] %s%n", s.startSec(), s.endSec(), s.text());
                        } else if (event instanceof ListenEvent.Done d) {
                            System.out.println("done: " + d.totalSegments() + " segments");
                        } else if (event instanceof ListenEvent.Error e) {
                            System.err.println("error: " + e.code() + " " + e.message());
                        }
                    }
                } catch (StreamException e) {
                    System.err.println("session ended: " + e.code() + " (retryable: " + e.retryable() + ")");
                }
            });
            reader.start();

            long start = System.nanoTime();
            byte[] chunk;
            for (long sent = 0; (chunk = in.readNBytes(CHUNK_BYTES)).length > 0; sent += chunk.length) {
                long due = start + sent * 1_000_000_000L / 32_000; // 32,000 bytes per second
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
                session.send(chunk);
            }
            session.finish();
            reader.join();
        }
    }
}

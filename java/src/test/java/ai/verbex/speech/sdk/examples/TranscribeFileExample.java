package ai.verbex.speech.sdk.examples;

import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.listen.job.Segment;
import ai.verbex.speech.sdk.listen.job.TranscribeRequest;

import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.listen.job.Segment;
import ai.verbex.speech.sdk.listen.job.TranscribeRequest;
import ai.verbex.speech.sdk.VerbexSpeech;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Transcribe a WAV or MP3 file (any size up to 100 MiB) and print the transcript.
 * Usage: TranscribeFileExample &lt;file&gt; [model] [language]; reads VERBEX_API_KEY and VERBEX_BASE_URL.
 */
public final class TranscribeFileExample {
    private TranscribeFileExample() {
    }

    public static void main(String[] args) {
        Path file = Path.of(args[0]);
        String model = args.length > 1 ? args[1] : "verbex_jp_stt_x1";
        String language = args.length > 2 ? args[2] : "ja";

        VerbexSpeech client = VerbexSpeech.builder().build();
        Job job = client.listen().transcribe(
                TranscribeRequest.builder().file(file).model(model).language(language).build(),
                Duration.ofSeconds(2), Duration.ofMinutes(30));

        if (!job.isCompleted()) {
            System.err.println("Job " + job.jobId() + " ended " + job.status()
                    + (job.error() == null ? "" : ": " + job.error().code()));
            System.exit(1);
        }
        for (Segment s : job.segments()) {
            System.out.printf("[%7.2f - %7.2f] %s%n", s.startSec(), s.endSec(), s.text());
        }
    }
}

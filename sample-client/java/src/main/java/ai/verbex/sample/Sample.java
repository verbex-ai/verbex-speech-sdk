package ai.verbex.sample;

import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.listen.model.SttModel;
import ai.verbex.speech.sdk.speak.AudioFormat;
import ai.verbex.speech.sdk.speak.SpeakRequest;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.voice.Voice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verbex Speech sample: mvn -q compile exec:java -Dexec.args="call.wav"
 *
 * <p>Reads VERBEX_API_KEY (and VERBEX_BASE_URL, if set) from the environment.
 */
public final class Sample {
    private static final String MODEL = "verbex_jp_stt_x1";
    private static final String LANGUAGE = "ja";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: Sample <audio.wav|audio.mp3>");
            System.exit(1);
        }

        VerbexSpeech client = VerbexSpeech.builder().build();
        System.out.println("API: " + client.baseUrl());

        for (SttModel m : client.listen().models()) {
            System.out.println("stt " + m.model() + " " + m.language());
        }
        List<Voice> voices = client.speak().voices();
        for (Voice v : voices) {
            System.out.println("tts " + v.model() + " " + v.code() + " " + v.language());
        }

        Job job = client.listen().transcribe(Path.of(args[0]), MODEL, LANGUAGE);
        System.out.println("job " + job.status());
        if (job.text() != null) {
            System.out.println(job.text());
        }

        Voice voice = voices.stream().filter(v -> v.language().equals(LANGUAGE)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no voice for " + LANGUAGE));
        SpeakAudio audio = client.speak().synthesize(SpeakRequest.builder()
                .model(voice.model()).voice(voice.code()).text("こんにちは")
                .format(AudioFormat.WAV)
                .build());
        Files.write(Path.of("hello.wav"), audio.audio());
        System.out.println("wrote hello.wav");
    }
}

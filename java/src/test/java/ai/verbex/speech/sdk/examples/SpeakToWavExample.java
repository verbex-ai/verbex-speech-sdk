package ai.verbex.speech.sdk.examples;

import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.SpeakRequest;

import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.SpeakRequest;
import ai.verbex.speech.sdk.VerbexSpeech;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Speak one sentence into a WAV file. Usage: SpeakToWavExample &lt;out.wav&gt; &lt;model&gt; &lt;voice&gt; &lt;text&gt;.
 * Asks for PCM and writes a WAV header with the real sizes.
 */
public final class SpeakToWavExample {
    private SpeakToWavExample() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        VerbexSpeech client = VerbexSpeech.builder().build();
        SpeakAudio audio = client.speak().synthesize(SpeakRequest.builder()
                .model(args[1]).voice(args[2]).text(args[3]).build());
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(wavHeader(audio.audio().length, 16_000, 1, 16));
            os.write(audio.audio());
        }
        System.out.println("wrote " + out + " (request " + audio.requestId() + ")");
    }

    static byte[] wavHeader(int dataBytes, int sampleRate, int channels, int bits) {
        int byteRate = sampleRate * channels * bits / 8;
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(byteRate).putShort((short) (channels * bits / 8)).putShort((short) bits)
                .put("data".getBytes()).putInt(dataBytes)
                .array();
    }
}

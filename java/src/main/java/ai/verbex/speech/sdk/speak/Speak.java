package ai.verbex.speech.sdk.speak;

import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.speak.stream.SpeakSession;
import ai.verbex.speech.sdk.speak.stream.SpeakSessions;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudio;
import ai.verbex.speech.sdk.speak.synthesis.SpeakAudioStream;
import ai.verbex.speech.sdk.speak.synthesis.Synthesizer;
import ai.verbex.speech.sdk.speak.voice.Voice;
import ai.verbex.speech.sdk.speak.voice.Voices;
import ai.verbex.speech.sdk.transport.HttpTransport;

import java.util.List;

/**
 * Text-to-speech (spec §5). Obtain with {@link VerbexSpeech#speak()}. The work is done in
 * {@code speak.voice}, {@code speak.synthesis} and {@code speak.stream}; this is the one place to call it.
 */
public final class Speak {
    private final Voices voices;
    private final Synthesizer synthesizer;
    private final SpeakSessions sessions;

    /** Made by {@link VerbexSpeech}; get it from {@code client.speak()}. */
    public Speak(HttpTransport transport) {
        this.voices = new Voices(transport);
        this.synthesizer = new Synthesizer(transport);
        this.sessions = new SpeakSessions(transport);
    }

    /** {@code GET /v1/speak/voices}. */
    public List<Voice> voices() {
        return voices.list();
    }

    /**
     * {@code POST /v1/speak}: the whole utterance in memory.
     *
     * @throws SpeakException with code {@code audio_incomplete} when the body ends with a transport error
     */
    public SpeakAudio synthesize(SpeakRequest request) {
        return synthesizer.synthesize(request);
    }

    /**
     * {@code POST /v1/speak}, the audio streamed as it is synthesised. Retries (spec §8) happen only
     * before the response starts, so never after audio has been received.
     */
    public SpeakAudioStream synthesizeStream(SpeakRequest request) {
        return synthesizer.synthesizeStream(request);
    }

    /** Opens {@code /v1/speak/stream}; usable at once (no ready frame). */
    public SpeakSession stream() {
        return sessions.open(null);
    }

    /** Opens {@code /v1/speak/stream} with {@code tag} query parameters. */
    public SpeakSession stream(List<String> tags) {
        return sessions.open(tags);
    }
}

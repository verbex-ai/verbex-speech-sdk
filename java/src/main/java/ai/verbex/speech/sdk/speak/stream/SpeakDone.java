package ai.verbex.speech.sdk.speak.stream;

/** The end of one utterance on the speak socket. */
public record SpeakDone(String requestId, Double audioSeconds) {
}

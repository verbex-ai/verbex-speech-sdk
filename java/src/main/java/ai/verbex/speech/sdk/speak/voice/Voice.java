package ai.verbex.speech.sdk.speak.voice;

/** A text-to-speech voice from {@code GET /v1/speak/voices}. Descriptions may be {@code null}. */
public record Voice(String model, String modelDisplayName, String code, String displayName, String language,
                    String gender, String style, String sampleUrl) {
}

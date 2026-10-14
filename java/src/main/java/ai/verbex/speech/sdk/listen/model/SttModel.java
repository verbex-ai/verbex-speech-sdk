package ai.verbex.speech.sdk.listen.model;

import java.util.List;

/** A speech-to-text model from {@code GET /v1/listen/models}. */
public record SttModel(String model, String displayName, String language, List<ModelMode> modes) {
}

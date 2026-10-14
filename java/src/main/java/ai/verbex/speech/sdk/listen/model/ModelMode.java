package ai.verbex.speech.sdk.listen.model;

import java.util.List;

/** A mode a model serves: {@code async} (file jobs) or {@code stream} (live sessions). */
public record ModelMode(String mode, List<ModelFeature> features) {
}

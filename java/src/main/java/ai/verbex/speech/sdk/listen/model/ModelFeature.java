package ai.verbex.speech.sdk.listen.model;

/** An option a model accepts on one mode. */
public record ModelFeature(String paramName, String displayName, String type, Integer maxItems,
                           String description) {
}

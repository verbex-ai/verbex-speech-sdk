package ai.verbex.speech.sdk.listen.job;

/** A transcribed phrase. */
public record Segment(Integer index, Double startSec, Double endSec, String text) {
}

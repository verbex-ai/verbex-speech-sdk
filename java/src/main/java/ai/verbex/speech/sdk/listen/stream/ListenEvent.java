package ai.verbex.speech.sdk.listen.stream;

import ai.verbex.speech.sdk.listen.job.Segment;

import ai.verbex.speech.sdk.error.StreamException;

import java.util.List;

/**
 * What a live transcription session delivers ({@link ListenSession#nextEvent()}).
 */
public sealed interface ListenEvent permits ListenEvent.SegmentEvent, ListenEvent.Configured, ListenEvent.Done,
        ListenEvent.Error {

    /** A transcribed phrase. */
    record SegmentEvent(int index, double startSec, double endSec, String text) implements ListenEvent {
        public Segment toSegment() {
            return new Segment(index, startSec, endSec, text);
        }
    }

    /** The answer to {@link ListenSession#configure(List)}: the hotwords now in force. */
    record Configured(List<String> hotwords) implements ListenEvent {
    }

    /** All audio has been transcribed; the session ends. */
    record Done(int totalSegments) implements ListenEvent {
    }

    /**
     * An error frame. On its own (for example a refused {@code configure}) the session stays open; if
     * the server then closes, the next {@code nextEvent()} throws {@link StreamException}.
     */
    record Error(String code, String message) implements ListenEvent {
    }
}

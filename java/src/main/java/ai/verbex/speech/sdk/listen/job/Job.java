package ai.verbex.speech.sdk.listen.job;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * A transcription job ({@code GET /v1/listen/{job_id}}, or a webhook body). Optional fields are
 * {@code null} when absent; {@code segments} is never {@code null}.
 */
public record Job(String jobId, String status, String model, String language, Double audioDurationSec,
                  Double progress, Integer segmentCount, Integer failedSegmentCount, String text,
                  List<Segment> segments, JobError error, Map<String, Object> metadata) {

    public Job {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** {@code status == "completed"}. */
    @JsonIgnore
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /** {@code completed}, {@code failed} or {@code cancelled}. */
    @JsonIgnore
    public boolean isFinished() {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }
}

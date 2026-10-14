package ai.verbex.speech.sdk.listen.job;

import ai.verbex.speech.sdk.Conformance;
import ai.verbex.speech.sdk.transport.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** conformance/responses.json: receipts, uploads and jobs. */
class JobResponsesTest {
    private static final JsonNode R = Conformance.load("responses.json");

    @Test
    void receipts() {
        assertEquals(new Receipt("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b", "queued", 1),
                Json.convert(R.get("receipt"), Receipt.class));
        assertNull(Json.convert(R.get("receipt_without_position"), Receipt.class).queuePosition());
    }

    @Test
    void uploadStartedHidesTheUrl() {
        UploadStarted u = Json.convert(R.get("upload_started"), UploadStarted.class);
        assertEquals("7f3c2a9e0b1d4c6f8a2e5b7d9c1f3a5e", u.uploadId());
        assertEquals(R.get("upload_started").get("upload_url").asText(), u.uploadUrl());
        assertEquals("PUT", u.method());
        assertEquals("0", u.headers().get("x-goog-if-generation-match"));
        assertEquals(600, u.uploadUrlExpiresInSeconds());
        assertEquals(1800, u.submitWindowSeconds());
        assertFalse(u.toString().contains("storage.example"));
        assertFalse(u.toString().contains("secret"));
    }

    @Test
    void jobs() {
        Job processing = Json.convert(R.get("job_processing"), Job.class);
        assertEquals("processing", processing.status());
        assertFalse(processing.isCompleted());
        assertNull(processing.audioDurationSec());
        assertNull(processing.error());
        assertEquals(0.4, processing.progress());

        Job completed = Json.convert(R.get("job_completed"), Job.class);
        assertTrue(completed.isCompleted());
        assertEquals(19.2, completed.audioDurationSec());
        assertEquals(2, completed.segments().size());
        assertEquals(new Segment(1, 17.48, 19.68, "おりかえしごれんらくいたします。"), completed.segments().get(1));
        assertEquals("c-42", completed.metadata().get("call_id"));

        Job failed = Json.convert(R.get("job_failed"), Job.class);
        assertEquals("failed", failed.status());
        assertNull(failed.language());
        assertNull(failed.text());
        assertEquals(new JobError("invalid_audio", "The audio could not be decoded."), failed.error());
        assertTrue(failed.segments().isEmpty());
    }
}

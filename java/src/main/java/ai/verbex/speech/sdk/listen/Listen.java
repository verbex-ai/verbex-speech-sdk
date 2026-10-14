package ai.verbex.speech.sdk.listen;

import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.error.ApiException;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.listen.job.Job;
import ai.verbex.speech.sdk.listen.job.Jobs;
import ai.verbex.speech.sdk.listen.job.Receipt;
import ai.verbex.speech.sdk.listen.job.Segment;
import ai.verbex.speech.sdk.listen.job.TranscribeRequest;
import ai.verbex.speech.sdk.listen.job.UploadException;
import ai.verbex.speech.sdk.listen.job.WaitTimeoutException;
import ai.verbex.speech.sdk.listen.model.Models;
import ai.verbex.speech.sdk.listen.model.SttModel;
import ai.verbex.speech.sdk.listen.stream.ListenSession;
import ai.verbex.speech.sdk.listen.stream.LiveSessions;
import ai.verbex.speech.sdk.transport.HttpTransport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Speech-to-text (spec §4). Obtain with {@link VerbexSpeech#listen()}. The work is done in
 * {@code listen.model}, {@code listen.job} and {@code listen.stream}; this is the one place to call it.
 */
public final class Listen {
    /** Default interval between polls of {@link #waitFor(String)}. */
    public static final Duration DEFAULT_POLL_INTERVAL = Jobs.DEFAULT_POLL_INTERVAL;
    /** Polls are never closer together than this. */
    public static final Duration MIN_POLL_INTERVAL = Jobs.MIN_POLL_INTERVAL;

    private final Models models;
    private final Jobs jobs;
    private final LiveSessions sessions;

    /** Made by {@link VerbexSpeech}; get it from {@code client.listen()}. */
    public Listen(HttpTransport transport) {
        this.models = new Models(transport);
        this.jobs = new Jobs(transport);
        this.sessions = new LiveSessions(transport);
    }

    /** {@code GET /v1/listen/models}. */
    public List<SttModel> models() {
        return models.list();
    }

    /**
     * Submits one file and returns the receipt (spec §4.2). Up to 1 MiB the audio goes in the
     * request; up to 100 MiB through the upload flow (§4.3); a URL is sent as JSON.
     *
     * @throws IllegalArgumentException for audio over 100 MiB (nothing is sent)
     * @throws UploadException          when the storage PUT fails (never retried)
     */
    public Receipt submit(TranscribeRequest request) {
        return jobs.submit(request);
    }

    /** {@link #submit} then {@link #waitFor(String)}: returns the finished job. */
    public Job transcribe(TranscribeRequest request) {
        return jobs.transcribe(request);
    }

    /** {@link #submit} then {@link #waitFor(String, Duration, Duration)}. */
    public Job transcribe(TranscribeRequest request, Duration pollInterval, Duration timeout) {
        return jobs.transcribe(request, pollInterval, timeout);
    }

    /** Transcribes a WAV or MP3 file with the defaults. */
    public Job transcribe(Path file, String model, String language) {
        return jobs.transcribe(file, model, language);
    }

    /** {@code GET /v1/listen/{job_id}}. */
    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    /** Polls every 2 s, without a time limit, until the job is completed, failed or cancelled. */
    public Job waitFor(String jobId) {
        return jobs.waitFor(jobId);
    }

    /**
     * Polls until the job is completed, failed or cancelled and returns it (it does not raise for
     * {@code failed}/{@code cancelled}: read {@link Job#status()}).
     *
     * @throws WaitTimeoutException past the timeout, carrying the last job seen
     */
    public Job waitFor(String jobId, Duration pollInterval, Duration timeout) {
        return jobs.waitFor(jobId, pollInterval, timeout);
    }

    /** Opens a live session without hotwords or tags. */
    public ListenSession stream(String model, String language) {
        return sessions.stream(model, language);
    }

    /**
     * Opens {@code /v1/listen/stream} and returns once the {@code ready} frame has arrived.
     *
     * @throws ApiException    when the handshake is refused with an HTTP status
     * @throws StreamException when the server refuses the session after the handshake
     */
    public ListenSession stream(String model, String language, List<String> hotwords, List<String> tags) {
        return sessions.stream(model, language, hotwords, tags);
    }

    /** Opens a session, sends every chunk, finishes, and returns all segments in order. */
    public List<Segment> transcribeStream(Iterable<byte[]> pcmChunks, String model, String language,
                                          List<String> hotwords, List<String> tags) {
        return sessions.transcribeStream(pcmChunks, model, language, hotwords, tags);
    }
}

package ai.verbex.speech.sdk.listen.job;

import ai.verbex.speech.sdk.error.BadRequestException;
import ai.verbex.speech.sdk.error.RateLimitException;
import ai.verbex.speech.sdk.listen.Listen;
import ai.verbex.speech.sdk.transport.CallKind;
import ai.verbex.speech.sdk.transport.HttpTransport;
import ai.verbex.speech.sdk.transport.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * File transcription: submitting (in the request, through the upload flow, or by URL), reading a
 * job and waiting for it (spec §4.2-4.4). Use {@link Listen}.
 */
public final class Jobs {
    /** Default interval between polls of {@link #waitFor(String)}. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    /** Polls are never closer together than this. */
    public static final Duration MIN_POLL_INTERVAL = Duration.ofMillis(500);
    /** Submit timeout: the gateway's limit, not the client's default. */
    static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(330);
    static final Duration UPLOAD_INCOMPLETE_DELAY = Duration.ofSeconds(1);

    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final HttpTransport transport;

    /** Made by {@link Listen}; use {@code client.listen()}. Not part of the API. */
    public Jobs(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * Submits one file and returns the receipt (spec §4.2). Up to 1 MiB the audio goes in the
     * request; up to 100 MiB through the upload flow (§4.3); a URL is sent as JSON.
     * <p>
     * Every submit sends an {@code Idempotency-Key}: the request's own, else a random UUID made once
     * for this call. Every retry of the call, including the upload flow's final submit, sends the same
     * key, so a retry after a lost answer returns the first request's receipt instead of a second job.
     *
     * @throws IllegalArgumentException for audio over 100 MiB (nothing is sent)
     * @throws UploadException          when the storage PUT fails (never retried)
     */
    public Receipt submit(TranscribeRequest request) {
        String path = "/v1/listen" + HttpTransport.queryString(HttpTransport.tagQuery(request.tags()));
        String key = request.idempotencyKey() != null ? request.idempotencyKey() : UUID.randomUUID().toString();
        if (request.isUrl()) {
            Map<String, Object> body = jsonBody(request);
            body.put("url", request.url());
            return submitJson(path, body, request.traceId(), key);
        }
        long size = request.size();
        if (size > TranscribeRequest.UPLOAD_MAX_BYTES) {
            throw new IllegalArgumentException("The audio is " + size + " bytes; at most "
                    + TranscribeRequest.UPLOAD_MAX_BYTES + " bytes (100 MiB) can be transcribed.");
        }
        if (size <= TranscribeRequest.DIRECT_MAX_BYTES) {
            return submitMultipart(path, request, key);
        }
        UploadStarted upload = startUpload(size, request.filename());
        putToStorage(upload, request, size);
        Map<String, Object> body = jsonBody(request);
        body.put("upload_id", upload.uploadId());
        for (int attempt = 0; ; attempt++) {
            try {
                return submitJson(path, body, request.traceId(), key);
            } catch (BadRequestException e) {
                if (e.status() != 409 || !"upload_incomplete".equals(e.code()) || attempt >= transport.maxRetries()) {
                    throw e;
                }
                transport.sleep(UPLOAD_INCOMPLETE_DELAY);
            }
        }
    }

    /** {@link #submit} then {@link #waitFor(String)}: returns the finished job. */
    public Job transcribe(TranscribeRequest request) {
        return waitFor(submit(request).jobId());
    }

    /** {@link #submit} then {@link #waitFor(String, Duration, Duration)}. */
    public Job transcribe(TranscribeRequest request, Duration pollInterval, Duration timeout) {
        return waitFor(submit(request).jobId(), pollInterval, timeout);
    }

    /** Transcribes a WAV or MP3 file with the defaults. */
    public Job transcribe(Path file, String model, String language) {
        return transcribe(TranscribeRequest.builder().file(file).model(model).language(language).build());
    }

    private Receipt submitMultipart(String path, TranscribeRequest r, String key) {
        Multipart form = new Multipart().file("file", r.filename(), r.readAll())
                .field("model", r.model()).field("language", r.language());
        for (String h : r.hotwords()) {
            form.field("hotwords", h);
        }
        if (r.metadata() != null) {
            form.field("metadata", new String(Json.write(r.metadata()), StandardCharsets.UTF_8));
        }
        if (r.webhookUrl() != null) {
            form.field("webhook_url", r.webhookUrl());
        }
        return post(path, r.traceId(), key, form.contentType(), form.build());
    }

    private Receipt submitJson(String path, Map<String, Object> body, String traceId, String key) {
        return post(path, traceId, key, "application/json", Json.write(body));
    }

    /** One submit with retries; each attempt gets a fresh publisher over the same bytes and the same key. */
    private Receipt post(String path, String traceId, String key, String contentType, byte[] body) {
        HttpResponse<byte[]> response = transport.send(CallKind.SUBMIT, traceId,
                trace -> transport.request(path, trace, SUBMIT_TIMEOUT).header("Content-Type", contentType)
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Receipt r = Json.read(response.body(), Receipt.class);
        boolean replayed = response.headers().firstValue("Idempotent-Replayed")
                .map(v -> v.trim().equalsIgnoreCase("true")).orElse(false);
        return new Receipt(r.jobId(), r.status(), r.queuePosition(), replayed);
    }

    private static Map<String, Object> jsonBody(TranscribeRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.model());
        body.put("language", r.language());
        if (!r.hotwords().isEmpty()) {
            body.put("hotwords", r.hotwords());
        }
        if (r.metadata() != null) {
            body.put("metadata", r.metadata());
        }
        if (r.webhookUrl() != null) {
            body.put("webhook_url", r.webhookUrl());
        }
        return body;
    }

    private UploadStarted startUpload(long size, String filename) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("filename", filename);
        return Json.read(transport.postJson(CallKind.UPLOAD_START, "/v1/listen/uploads", Json.write(body),
                transport.timeout(), null), UploadStarted.class);
    }

    /**
     * The storage PUT: exactly the headers from the upload answer, none of ours (no Authorization,
     * no X-Trace-Id). Content-Length comes from the body publisher's declared length, since
     * java.net.http does not let callers set it. No request timeout: only the connect timeout applies.
     */
    private void putToStorage(UploadStarted upload, TranscribeRequest r, long size) {
        HttpRequest.Builder put;
        try {
            put = HttpRequest.newBuilder(URI.create(upload.uploadUrl()));
        } catch (RuntimeException e) {
            throw new UploadException(0, "The upload URL from the API is not a valid URL.");
        }
        Map<String, String> headers = upload.headers() == null ? Map.of() : upload.headers();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("Content-Length")) {
                if (!String.valueOf(size).equals(h.getValue().trim())) {
                    throw new UploadException(0, "The upload's Content-Length does not match the file size.");
                }
                continue;
            }
            try {
                put.header(h.getKey(), h.getValue());
            } catch (IllegalArgumentException e) {
                throw new UploadException(0, "The storage header " + h.getKey() + " cannot be sent by java.net.http.");
            }
        }
        String method = upload.method() == null ? "PUT" : upload.method();
        HttpResponse<byte[]> response;
        try {
            response = transport.httpClient().send(put.method(method, r.publisher()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UploadException(0, "The upload to storage failed: " + e.getClass().getSimpleName()
                    + ". Start a new upload.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UploadException(0, "Interrupted during the upload to storage.");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new UploadException(status, "The storage service refused the upload with HTTP " + status
                    + (status == 412 ? " (the upload URL was already used)" : "") + ". Start a new upload.");
        }
    }

    /** {@code GET /v1/listen/{job_id}}. */
    public Job get(String jobId) {
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("jobId is not a job id");
        }
        return Json.read(transport.get("/v1/listen/" + jobId), Job.class);
    }

    /** Polls every 2 s, without a time limit, until the job is completed, failed or cancelled. */
    public Job waitFor(String jobId) {
        return waitFor(jobId, DEFAULT_POLL_INTERVAL, null);
    }

    /**
     * Polls until the job is completed, failed or cancelled and returns it (it does not raise for
     * {@code failed}/{@code cancelled}: read {@link Job#status()}). A 429 while polling waits
     * {@code Retry-After} (else the interval) and continues.
     *
     * @param pollInterval at least 0.5 s; shorter values are raised to 0.5 s
     * @param timeout      {@code null} for no limit
     * @throws WaitTimeoutException past the timeout, carrying the last job seen
     */
    public Job waitFor(String jobId, Duration pollInterval, Duration timeout) {
        Duration interval = pollInterval == null || pollInterval.compareTo(MIN_POLL_INTERVAL) < 0
                ? MIN_POLL_INTERVAL : pollInterval;
        long start = transport.nanoTime();
        Job last = null;
        while (true) {
            Duration wait = interval;
            try {
                last = get(jobId);
                if (last.isFinished()) {
                    return last;
                }
            } catch (RateLimitException e) {
                if (e.retryAfter() != null) {
                    wait = Duration.ofSeconds(e.retryAfter());
                }
            }
            if (timeout != null) {
                Duration remaining = timeout.minusNanos(transport.nanoTime() - start);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new WaitTimeoutException("Job " + jobId + " did not finish within " + timeout + ".", last);
                }
                if (wait.compareTo(remaining) > 0) {
                    wait = remaining;
                }
            }
            transport.sleep(wait);
        }
    }
}

package ai.verbex.speech.sdk.listen.job;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One file to transcribe (spec §4.2): exactly one source ({@link Builder#file}, {@link Builder#bytes},
 * {@link Builder#stream} with its size, or {@link Builder#url}), plus model and language.
 * Files of up to 1 MiB go in the request; larger ones (up to 100 MiB) use the upload flow.
 */
public final class TranscribeRequest {
    /** Files up to this size are sent in the request. */
    public static final long DIRECT_MAX_BYTES = 1_048_576L;
    /** Files up to this size are sent by the upload flow; larger ones are refused locally. */
    public static final long UPLOAD_MAX_BYTES = 104_857_600L;

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final Path file;
    private final byte[] bytes;
    private final InputStream stream;
    private final long streamSize;
    private final String url;
    private final String model;
    private final String language;
    private final String filename;
    private final List<String> hotwords;
    private final Map<String, Object> metadata;
    private final String webhookUrl;
    private final List<String> tags;
    private final String traceId;
    private final String idempotencyKey;

    private TranscribeRequest(Builder b) {
        this.file = b.file;
        this.bytes = b.bytes;
        this.stream = b.stream;
        this.streamSize = b.streamSize;
        this.url = b.url;
        this.model = b.model;
        this.language = b.language;
        this.hotwords = b.hotwords == null ? List.of() : List.copyOf(b.hotwords);
        this.metadata = b.metadata == null ? null : new LinkedHashMap<>(b.metadata);
        this.webhookUrl = b.webhookUrl;
        this.tags = b.tags == null ? List.of() : List.copyOf(b.tags);
        this.traceId = b.traceId;
        this.idempotencyKey = b.idempotencyKey;
        String name = b.filename;
        if (name == null && file != null && file.getFileName() != null) {
            name = file.getFileName().toString();
        }
        this.filename = name == null ? "audio.wav" : name;
    }

    public static Builder builder() {
        return new Builder();
    }

    String model() {
        return model;
    }

    String language() {
        return language;
    }

    String url() {
        return url;
    }

    String filename() {
        return filename;
    }

    List<String> hotwords() {
        return hotwords;
    }

    Map<String, Object> metadata() {
        return metadata;
    }

    String webhookUrl() {
        return webhookUrl;
    }

    List<String> tags() {
        return tags;
    }

    String traceId() {
        return traceId;
    }

    /** The caller's key, or {@code null} when the SDK makes one per submit. */
    String idempotencyKey() {
        return idempotencyKey;
    }

    boolean isUrl() {
        return url != null;
    }

    /** The audio's size in bytes. */
    long size() {
        if (bytes != null) {
            return bytes.length;
        }
        if (stream != null) {
            return streamSize;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the size of the audio file", e);
        }
    }

    /** The whole audio in memory (only for files of at most 1 MiB). */
    byte[] readAll() {
        try {
            if (bytes != null) {
                return bytes;
            }
            if (file != null) {
                return Files.readAllBytes(file);
            }
            byte[] data = stream.readNBytes((int) streamSize);
            if (data.length != streamSize) {
                throw new IllegalArgumentException("the stream ended after " + data.length
                        + " bytes, before its declared size of " + streamSize);
            }
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the audio", e);
        }
    }

    /** A body publisher of the declared length, for the storage PUT. */
    HttpRequest.BodyPublisher publisher() {
        try {
            if (bytes != null) {
                return HttpRequest.BodyPublishers.ofByteArray(bytes);
            }
            if (file != null) {
                return HttpRequest.BodyPublishers.ofFile(file);
            }
            return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> stream),
                    streamSize);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the audio file", e);
        }
    }

    @Override
    public String toString() {
        String source = url != null ? "url=<redacted>" : file != null ? "file=" + file
                : bytes != null ? "bytes=" + bytes.length : "stream=" + streamSize;
        return "TranscribeRequest[" + source + ", model=" + model + ", language=" + language + ", filename="
                + filename + ", hotwords=" + hotwords + ", tags=" + tags + "]";
    }

    public static final class Builder {
        private Path file;
        private byte[] bytes;
        private InputStream stream;
        private long streamSize = -1;
        private String url;
        private String model;
        private String language;
        private String filename;
        private List<String> hotwords;
        private Map<String, ?> metadata;
        private String webhookUrl;
        private List<String> tags;
        private String traceId;
        private String idempotencyKey;

        private Builder() {
        }

        /** Audio from a file; its name is the default {@code filename}. */
        public Builder file(Path file) {
            this.file = Objects.requireNonNull(file, "file");
            return this;
        }

        /** Audio in memory. */
        public Builder bytes(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            return this;
        }

        /**
         * Audio from a stream of exactly {@code size} bytes. The stream is read once and not closed:
         * the caller owns it.
         */
        public Builder stream(InputStream stream, long size) {
            this.stream = Objects.requireNonNull(stream, "stream");
            this.streamSize = size;
            return this;
        }

        /** Audio at an https URL that the API downloads. */
        public Builder url(String url) {
            this.url = Objects.requireNonNull(url, "url");
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /** For bytes and streams; defaults to the path's name, else {@code audio.wav}. */
        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public Builder hotwords(List<String> hotwords) {
            this.hotwords = hotwords;
            return this;
        }

        /** Your own JSON object (at most 4096 bytes serialised, checked by the API). */
        public Builder metadata(Map<String, ?> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder webhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
            return this;
        }

        /** Sent as repeated {@code tag} query parameters. */
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        /** Your own {@code X-Trace-Id} (a UUID) for the submit; a fresh one is generated otherwise. */
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * Your own {@code Idempotency-Key} for the submit: 1 to 255 visible ASCII characters. Without
         * one, the SDK makes a random UUID per submit call. Pass your own (saved with the work) when
         * you may submit the same file again from another process, for example after a restart: the
         * API answers a repeated key with the first request's receipt for 24 hours.
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /**
         * @throws IllegalArgumentException unless exactly one source, a model and a language are
         *                                  given, and any idempotency key is 1 to 255 visible ASCII characters
         */
        public TranscribeRequest build() {
            int sources = (file != null ? 1 : 0) + (bytes != null ? 1 : 0) + (stream != null ? 1 : 0)
                    + (url != null ? 1 : 0);
            if (sources != 1) {
                throw new IllegalArgumentException("give exactly one of file, bytes, stream and url");
            }
            if (stream != null && streamSize < 0) {
                throw new IllegalArgumentException("a stream needs its size in bytes");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model is required");
            }
            if (language == null || language.isBlank()) {
                throw new IllegalArgumentException("language is required");
            }
            if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
                throw new IllegalArgumentException("idempotencyKey must be 1 to 255 visible ASCII characters");
            }
            return new TranscribeRequest(this);
        }
    }
}

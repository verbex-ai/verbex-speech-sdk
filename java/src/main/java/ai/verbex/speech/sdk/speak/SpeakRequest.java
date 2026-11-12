package ai.verbex.speech.sdk.speak;

import ai.verbex.speech.sdk.transport.Idempotency;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One utterance for {@code POST /v1/speak} (spec §5.2). Generation settings (for example
 * {@code temperature}) are sent as top-level JSON fields.
 */
public final class SpeakRequest {
    static final Set<String> CORE_FIELDS = Set.of("model", "voice", "text", "request_id");

    private final String model;
    private final String voice;
    private final String text;
    private final String requestId;
    private final Map<String, Object> settings;
    private final AudioFormat format;
    private final List<String> tags;
    private final String traceId;
    private final String idempotencyKey;

    private SpeakRequest(Builder b) {
        this.model = b.model;
        this.voice = b.voice;
        this.text = b.text;
        this.requestId = b.requestId;
        this.settings = b.settings == null ? Map.of() : new LinkedHashMap<>(b.settings);
        this.format = b.format;
        this.tags = b.tags == null ? List.of() : List.copyOf(b.tags);
        this.traceId = b.traceId;
        this.idempotencyKey = b.idempotencyKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The audio format asked for. */
    public AudioFormat format() {
        return format;
    }

    /** The {@code tag} query parameters. */
    public List<String> tags() {
        return tags;
    }

    /** The trace id to send, or null for a fresh one. */
    public String traceId() {
        return traceId;
    }

    /** The caller's {@code Idempotency-Key}, or {@code null} when the SDK makes one per call. */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** The JSON body the SDK sends: the core fields, then the settings. */
    public Map<String, Object> body() {
        return body(model, voice, text, requestId, settings);
    }

    /** The JSON body of one utterance. Used by the speak socket too. */
    public static Map<String, Object> body(String model, String voice, String text, String requestId,
                                    Map<String, ?> settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("voice", voice);
        body.put("text", text);
        if (requestId != null) {
            body.put("request_id", requestId);
        }
        if (settings != null) {
            for (Map.Entry<String, ?> e : settings.entrySet()) {
                if (CORE_FIELDS.contains(e.getKey())) {
                    throw new IllegalArgumentException("setting " + e.getKey() + " would replace a request field");
                }
                body.put(e.getKey(), e.getValue());
            }
        }
        return body;
    }

    @Override
    public String toString() {
        return "SpeakRequest[model=" + model + ", voice=" + voice + ", textLength="
                + (text == null ? 0 : text.length()) + ", requestId=" + requestId + ", format=" + format + "]";
    }

    public static final class Builder {
        private String model;
        private String voice;
        private String text;
        private String requestId;
        private Map<String, ?> settings;
        private AudioFormat format = AudioFormat.PCM;
        private List<String> tags;
        private String traceId;
        private String idempotencyKey;

        private Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** Your id for the utterance; the API generates one when absent. Echoed in {@code X-Request-Id}. */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /** Generation settings the model offers, sent as top-level fields. */
        public Builder settings(Map<String, ?> settings) {
            this.settings = settings;
            return this;
        }

        /** {@link AudioFormat#PCM} (default) or {@link AudioFormat#WAV}. */
        public Builder format(AudioFormat format) {
            this.format = format == null ? AudioFormat.PCM : format;
            return this;
        }

        /** Sent as repeated {@code tag} query parameters. */
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        /** Your own {@code X-Trace-Id} (a UUID); a fresh one is generated otherwise. */
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * Your own {@code Idempotency-Key} for the speak: 1 to 255 visible ASCII characters. Without
         * one, the SDK makes a random UUID per call. Pass your own (saved with the work) when you may
         * ask for the same utterance again from another process: for 24 hours the API speaks a repeated
         * key again without billing it, up to 3 times.
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /**
         * @throws IllegalArgumentException without model, voice or text, when a setting names a core
         *                                  field, or unless any idempotency key is 1 to 255 visible ASCII characters
         */
        public SpeakRequest build() {
            if (model == null || voice == null || text == null) {
                throw new IllegalArgumentException("model, voice and text are required");
            }
            Idempotency.check(idempotencyKey);
            SpeakRequest r = new SpeakRequest(this);
            r.body();
            return r;
        }
    }
}

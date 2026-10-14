package ai.verbex.speech.sdk.listen.job;

import java.util.Map;

/**
 * The answer to {@code POST /v1/listen/uploads}. {@code uploadUrl} is a credential and is left out
 * of {@link #toString()}.
 */
record UploadStarted(String uploadId, String uploadUrl, String method, Map<String, String> headers,
                     Integer uploadUrlExpiresInSeconds, Integer submitWindowSeconds) {

    @Override
    public String toString() {
        return "UploadStarted[uploadId=" + uploadId + ", uploadUrl=<redacted>, method=" + method
                + ", headers=" + (headers == null ? null : headers.keySet())
                + ", uploadUrlExpiresInSeconds=" + uploadUrlExpiresInSeconds
                + ", submitWindowSeconds=" + submitWindowSeconds + "]";
    }
}

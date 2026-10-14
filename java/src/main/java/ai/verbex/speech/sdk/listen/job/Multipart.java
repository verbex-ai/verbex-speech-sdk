package ai.verbex.speech.sdk.listen.job;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A small multipart/form-data writer (the body is at most about 1 MiB). */
final class Multipart {
    private final String boundary = "verbex-" + UUID.randomUUID();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    Multipart field(String name, String value) {
        // No per-part Content-Type, as curl -F sends it; values are UTF-8.
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(value);
        write("\r\n");
        return this;
    }

    Multipart file(String name, String filename, byte[] data) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + quote(filename) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(data);
        write("\r\n");
        return this;
    }

    byte[] build() {
        write("--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static String quote(String s) {
        return s.replace("\r", "").replace("\n", "").replace("\"", "%22");
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}

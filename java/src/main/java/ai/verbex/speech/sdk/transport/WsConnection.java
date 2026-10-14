package ai.verbex.speech.sdk.transport;

import ai.verbex.speech.sdk.VerbexSpeech;
import ai.verbex.speech.sdk.error.ApiConnectionException;
import ai.verbex.speech.sdk.error.ApiTimeoutException;
import ai.verbex.speech.sdk.error.StreamException;
import ai.verbex.speech.sdk.error.VerbexException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared machinery of the SDK's {@code listen} and {@code speak} packages. Public only because Java
 * cannot share a type between packages otherwise: <b>not part of the API</b>, and it may change in any
 * release. Use {@link VerbexSpeech}.
 */
public final class WsConnection implements WebSocket.Listener {

    public sealed interface Frame permits Text, Binary, Closed {
    }

    public record Text(String text) implements Frame {
    }

    public record Binary(byte[] data) implements Frame {
    }

    public record Closed(int code, String reason) implements Frame {
    }

    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private final AtomicBoolean closedSeen = new AtomicBoolean();
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private final Duration sendTimeout;
    private volatile WebSocket ws;
    private volatile Integer closeCode;

    private WsConnection(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    /**
     * Opens the socket with the API key header. Never retried (spec §8).
     *
     * @throws ApiException for a handshake refused with an HTTP status
     */
    public static WsConnection open(HttpTransport transport, URI uri) {
        WsConnection conn = new WsConnection(transport.timeout);
        CompletableFuture<WebSocket> future = transport.http.newWebSocketBuilder()
                .connectTimeout(transport.timeout)
                .header("Authorization", transport.authorization())
                .header("User-Agent", transport.userAgent)
                .header("X-Trace-Id", UUID.randomUUID().toString())
                .buildAsync(uri, conn);
        try {
            conn.ws = future.get(transport.timeout.toMillis(), TimeUnit.MILLISECONDS);
            return conn;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApiTimeoutException("The WebSocket handshake did not complete within the timeout.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerbexException("Interrupted while opening the WebSocket.");
        } catch (ExecutionException e) {
            throw handshakeFailure(e.getCause());
        }
    }

    private static VerbexException handshakeFailure(Throwable cause) {
        Throwable c = cause;
        while (c != null && !(c instanceof WebSocketHandshakeException) && !(c instanceof HttpTimeoutException)
                && c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        if (c instanceof WebSocketHandshakeException hs && hs.getResponse() != null) {
            var response = hs.getResponse();
            Object body = response.body();
            byte[] bytes = body instanceof byte[] b ? b
                    : body instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
            return Errors.fromResponse(response.statusCode(),
                    name -> response.headers().firstValue(name).orElse(null), bytes);
        }
        if (c instanceof HttpTimeoutException) {
            return new ApiTimeoutException("The WebSocket handshake did not complete within the timeout.");
        }
        return new ApiConnectionException("Could not open the WebSocket: "
                + (c == null ? "unknown" : c.getClass().getSimpleName()));
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            frames.add(new Text(text.toString()));
            text.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binary.writeBytes(chunk);
        if (last) {
            frames.add(new Binary(binary.toByteArray()));
            binary.reset();
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed(statusCode, reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed(1006, error.getClass().getSimpleName());
    }

    private void closed(int code, String reason) {
        if (closedSeen.compareAndSet(false, true)) {
            closeCode = code;
            frames.add(new Closed(code, reason));
        }
    }

    /** Blocks for the next frame. */
    public Frame take() {
        try {
            return frames.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerbexException("Interrupted while waiting for the server.");
        }
    }

    /** The next frame within {@code timeout}, or {@code null}. */
    public Frame poll(Duration timeout) {
        try {
            return frames.poll(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerbexException("Interrupted while waiting for the server.");
        }
    }

    public synchronized void sendText(String message) {
        await(ws.sendText(message, true));
    }

    public synchronized void sendBinary(byte[] data, int offset, int length) {
        await(ws.sendBinary(ByteBuffer.wrap(data, offset, length), true));
    }

    private void await(CompletableFuture<WebSocket> sent) {
        try {
            sent.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerbexException("Interrupted while sending.");
        } catch (ExecutionException | TimeoutException e) {
            throw new StreamException("stream_closed", "The session is closed; nothing more can be sent.", closeCode);
        }
    }

    public boolean isClosed() {
        return closedSeen.get();
    }

    /** Sends a normal close, then drops the connection if the server has not closed within a second. */
    public void close() {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        try {
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!socket.isInputClosed() && !closedSeen.get() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // closing anyway
        } finally {
            socket.abort();
        }
    }
}

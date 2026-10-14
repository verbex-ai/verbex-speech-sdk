package ai.verbex.speech.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replays a conformance script on an ephemeral loopback port. Steps: {"server": frame} (a JSON text
 * frame, or {"binary_bytes": n}, or {"text": {...}}), {"client": expected}, {"close": code} (1006 drops
 * the connection without a close frame), {"ping": true}, {"done_for_last": seconds} (a speak done
 * frame echoing the last request_id received).
 */
final class ScriptedWsServer extends WebSocketServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<JsonNode> steps;
    private int next = 0;
    private String lastRequestId;
    private final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);
    final List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
    final List<Object> received = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger pongs = new AtomicInteger();
    volatile String resource;
    volatile String authorization;
    volatile String userAgent;

    ScriptedWsServer(JsonNode script) {
        super(new InetSocketAddress("127.0.0.1", 0));
        List<JsonNode> s = new ArrayList<>();
        script.forEach(s::add);
        this.steps = s;
        setReuseAddr(true);
        start();
        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("server did not start");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static ScriptedWsServer of(String json) {
        try {
            return new ScriptedWsServer(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + getPort();
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public synchronized void onOpen(WebSocket conn, ClientHandshake handshake) {
        resource = handshake.getResourceDescriptor();
        authorization = handshake.getFieldValue("Authorization");
        userAgent = handshake.getFieldValue("User-Agent");
        emit(conn);
    }

    @Override
    public synchronized void onMessage(WebSocket conn, String message) {
        received.add(message);
        JsonNode got;
        try {
            got = MAPPER.readTree(message);
        } catch (Exception e) {
            mismatches.add("not JSON: " + message);
            return;
        }
        if (got.has("request_id")) {
            lastRequestId = got.get("request_id").asText();
        }
        expectClient(conn, "text", got);
    }

    @Override
    public synchronized void onMessage(WebSocket conn, ByteBuffer message) {
        received.add(message.remaining());
        expectClient(conn, "binary_bytes", MAPPER.getNodeFactory().numberNode(message.remaining()));
    }

    private void expectClient(WebSocket conn, String kind, JsonNode got) {
        if (next >= steps.size() || !steps.get(next).has("client")) {
            mismatches.add("unexpected client " + kind + ": " + got);
            return;
        }
        JsonNode expected = steps.get(next).get("client").get(kind);
        boolean any = expected != null && expected.isTextual() && "any".equals(expected.asText()) && "text".equals(kind);
        if (!any && (expected == null || !expected.equals(got))) {
            mismatches.add("expected " + steps.get(next).get("client") + " got " + kind + " " + got);
        }
        next++;
        emit(conn);
    }

    private void emit(WebSocket conn) {
        while (next < steps.size() && !steps.get(next).has("client")) {
            JsonNode step = steps.get(next++);
            if (step.has("close")) {
                int code = step.get("close").asInt();
                if (code == 1006) {
                    // Let queued frames flush, then drop the TCP connection without a close frame.
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        conn.closeConnection(1006, "dropped");
                    }).start();
                } else {
                    conn.close(code, "");
                }
            } else if (step.has("ping")) {
                conn.sendPing();
            } else if (step.has("done_for_last")) {
                ObjectNode done = MAPPER.createObjectNode().put("event", "done").put("request_id", lastRequestId)
                        .put("audio_seconds", step.get("done_for_last").asDouble());
                conn.send(done.toString());
            } else {
                JsonNode frame = step.get("server");
                if (frame.has("binary_bytes")) {
                    conn.send(ByteBuffer.wrap(new byte[frame.get("binary_bytes").asInt()]));
                } else if (frame.path("text").isObject()) {
                    conn.send(frame.get("text").toString());
                } else {
                    conn.send(frame.toString());
                }
            }
        }
        if (next >= steps.size()) {
            finished.countDown();
        }
    }

    @Override
    public void onWebsocketPong(WebSocket conn, Framedata f) {
        pongs.incrementAndGet();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
    }

    void shutdown() {
        try {
            stop(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

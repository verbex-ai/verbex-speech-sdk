package ai.verbex.speech.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** A scripted HTTP server on an ephemeral loopback port. */
final class FakeApi implements AutoCloseable {

    record Recorded(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        boolean hasHeader(String name) {
            return header(name) != null;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange, Recorded request) throws IOException;
    }

    private final HttpServer server;
    private final Map<String, Deque<Handler>> routes = new HashMap<>();
    final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

    FakeApi() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::dispatch);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queue handlers for a route; the last one repeats. */
    synchronized FakeApi on(String method, String path, Handler... handlers) {
        routes.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>()).addAll(List.of(handlers));
        return this;
    }

    List<Recorded> requests(String method, String path) {
        synchronized (requests) {
            return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Recorded rec = new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                ex.getRequestURI().getRawQuery(), new HashMap<>(ex.getRequestHeaders()), body);
        requests.add(rec);
        Handler h;
        synchronized (this) {
            Deque<Handler> q = routes.get(rec.method() + " " + rec.path());
            h = q == null || q.isEmpty() ? null : (q.size() > 1 ? q.poll() : q.peek());
        }
        try {
            if (h == null) {
                respond(404, Map.of(), "{\"error\":{\"code\":\"route_not_found\",\"message\":\"no\"}}").handle(ex, rec);
            } else {
                h.handle(ex, rec);
            }
        } finally {
            ex.close();
        }
    }

    static Handler respond(int status, Map<String, String> headers, String body) {
        return respond(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    static Handler respond(int status, Map<String, String> headers, byte[] body) {
        return (ex, rec) -> {
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            if (!headers.containsKey("Content-Type")) {
                ex.getResponseHeaders().add("Content-Type", "application/json");
            }
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        };
    }

    static Handler json(int status, String body) {
        return respond(status, Map.of(), body);
    }

    static Handler error(int status, String code, Map<String, String> headers) {
        return respond(status, headers, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"m\",\"request_id\":\"r\"}}");
    }

    static Handler delayed(long millis, Handler then) {
        return (ex, rec) -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            then.handle(ex, rec);
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

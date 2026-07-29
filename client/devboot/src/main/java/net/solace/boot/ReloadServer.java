package net.solace.boot;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The reload trigger, deliberately outside the reloadable layer.
 *
 * <p>Solace's own control API cannot own this. It runs inside the layer, so a reload tears down the
 * HTTP server that is serving the reload request - {@code ApiServer.close()} calls
 * {@code shutdownNow()} on the very pool thread handling it, and the caller gets a dropped socket
 * instead of a result. This server is built from application-classloader classes on its own thread and
 * is never torn down, so the request that triggers a reload survives it and reports the real outcome.
 *
 * <p>Loopback only, and read-mostly: {@code GET /status} and {@code POST /reload}.
 */
public final class ReloadServer implements AutoCloseable {
    public static final String PORT_PROPERTY = "solace.reload.port";
    private static final int DEFAULT_PORT = 7781;

    private final Gson gson = new Gson();
    private final LayerReloader reloader;
    private final Supplier<List<Path>> jars;
    private HttpServer server;

    public ReloadServer(LayerReloader reloader, Supplier<List<Path>> jars) {
        this.reloader = reloader;
        this.jars = jars;
    }

    public void start() throws IOException {
        var port = resolvePort();
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);

        // Single-threaded: reloads must serialise anyway, and a reload holds its thread for seconds.
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "solace-boot-http");
            thread.setDaemon(true);
            return thread;
        }));

        server.createContext("/status", this::handleStatus);
        server.createContext("/reload", this::handleReload);
        server.start();

        System.out.println("[layer] reload endpoint on http://127.0.0.1:" + port
                + " (GET /status, POST /reload)");
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        send(exchange, 200, gson.toJson(reloader.status()));
    }

    private void handleReload(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        // Blocking is safe here and nowhere else: this thread belongs to the bootstrap, so teardown
        // never touches it. The response therefore carries the real outcome.
        var result = reloader.reload(jars.get());
        var ok = Boolean.TRUE.equals(result.get("reloaded"));
        send(exchange, ok ? 200 : 500, gson.toJson(result));
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int resolvePort() {
        var configured = System.getProperty(PORT_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}

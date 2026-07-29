package net.solace.loader.controlapi;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * The loopback HTTP control plane. Ported from easy-rl's {@code APIServer}, with token
 * authentication and browser-origin rejection added - see {@link ApiAccessToken} for why loopback
 * binding alone is not enough.
 *
 * <p>Routes are registered by the owning plugin via {@link #addContext}; this class only owns
 * {@code /api/status}, {@code /api/commands} and {@code /api/command}.
 */
@Slf4j
public final class ApiServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    public static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    /**
     * Each SSE stream pins a pool thread for its whole life, so the ceiling here is also the cap on
     * concurrent log streams plus in-flight commands. easy-rl uses 8, which nine streams starve into
     * rejecting ordinary commands with a 500.
     */
    private static final int MAX_POOL_THREADS = 16;

    private final Gson gson;
    private final ApiCommandRegistry commands;
    private final ApiAccessToken token;
    private final int requestedPort;
    private final int maximumResponseBytes;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final Map<String, HttpHandler> extraContexts = new LinkedHashMap<>();
    private HttpServer server;
    private ThreadPoolExecutor executor;

    public ApiServer(Gson gson, ApiCommandRegistry commands, ApiAccessToken token, int port) {
        this(gson, commands, token, port, MAX_RESPONSE_BYTES);
    }

    ApiServer(Gson gson, ApiCommandRegistry commands, ApiAccessToken token, int port,
              int maximumResponseBytes) {
        this.gson = gson;
        this.commands = commands;
        this.token = token;
        this.requestedPort = port;
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        // corePoolSize == maximumPoolSize is load-bearing, not a tuning choice. ThreadPoolExecutor
        // only creates threads beyond the core size once the queue is FULL, so easy-rl's (core 2,
        // max 8, queue 128) is really a 2-thread server: two long-lived SSE streams pin both threads
        // and every later request sits in the queue forever instead of being served or rejected.
        // allowCoreThreadTimeOut keeps idle threads from being retained.
        executor = new ThreadPoolExecutor(
                MAX_POOL_THREADS, MAX_POOL_THREADS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                runnable -> {
                    var thread = new Thread(runnable, "solace-controlapi-" + threadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);

        // The explicit loopback address matters: new InetSocketAddress(port) binds every interface.
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 32);
        } catch (BindException e) {
            // Leave nothing behind for the caller to trip over: this pool is useless now, and an
            // abandoned one would keep 16 idle threads and pin this classloader generation.
            executor.shutdownNow();
            executor = null;
            throw describeBindFailure(e);
        }
        server.setExecutor(executor);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/commands", this::handleCommands);
        server.createContext("/api/command", this::handleCommand);
        for (var route : extraContexts.entrySet()) {
            server.createContext(route.getKey(), route.getValue());
        }

        running.set(true);
        server.start();
    }

    /**
     * Rewrites a bare "Address already in use" into something that names the port and, where it can,
     * the process holding it.
     *
     * <p>Worth the effort because this failure lies about itself. Whatever already owns the port keeps
     * answering requests, so the API looks up while serving a different process's - or an older layer
     * generation's - code, and the only visible symptom is that changes appear not to take effect.
     */
    private BindException describeBindFailure(BindException cause) {
        var owner = ControlApiEndpoints.describeOwner(requestedPort)
                .orElse("an unknown process (run: lsof -nP -iTCP:" + requestedPort + " -sTCP:LISTEN)");
        var detailed = new BindException("127.0.0.1:" + requestedPort + " is already held by "
                + owner + "; the control API did not start");
        detailed.initCause(cause);
        return detailed;
    }

    /**
     * Registers an additional route. Call before {@link #start()}; routes added afterwards are
     * attached to the running server immediately.
     */
    public synchronized void addContext(String path, HttpHandler handler) {
        extraContexts.put(path, handler);
        if (running.get() && server != null) {
            server.createContext(path, handler);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** The port actually bound, which differs from the requested one when 0 was asked for. */
    public int getPort() {
        var current = server;
        return current == null ? requestedPort : current.getAddress().getPort();
    }

    // -- authentication ------------------------------------------------------------------------

    /**
     * Rejects anything that is not an authenticated, non-browser request. Returns false when a
     * response has already been sent.
     *
     * <p>The {@code Origin} check is belt-and-braces: a page cannot set {@code X-Solace-Token}
     * without a preflight, and no {@code Access-Control-Allow-*} header is ever emitted, so the
     * preflight fails. Refusing the header outright means a future CORS mistake cannot open a hole.
     */
    public boolean authorize(HttpExchange exchange) throws IOException {
        var origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isEmpty()) {
            sendJson(exchange, 403, ApiResponse.failure(
                    requestId(exchange), null, "UNAUTHORIZED", "Browser origins are not accepted"));
            return false;
        }

        // Tokenless by default - see ApiAccessToken for why the Origin and Content-Type checks carry
        // the real weight here.
        if (!token.matches(exchange.getRequestHeaders().getFirst("X-Solace-Token"))) {
            sendJson(exchange, 401, ApiResponse.failure(
                    requestId(exchange), null, "UNAUTHORIZED", "A valid X-Solace-Token header is required"));
            return false;
        }

        return true;
    }

    // -- routes --------------------------------------------------------------------------------

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET") || !authorize(exchange)) {
            return;
        }
        CaptureClock.clear();
        try {
            var status = commands.execute("client.status", null);
            sendJson(exchange, 200, ApiResponse.success(requestId(exchange), "client.status", status));
        } catch (Exception e) {
            sendFailure(exchange, null, "client.status", e);
        }
    }

    private void handleCommands(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET") || !authorize(exchange)) {
            return;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("commands", commands.names());
        result.put("count", commands.names().size());
        sendJson(exchange, 200, ApiResponse.success(requestId(exchange), "commands.list", result));
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST") || !authorize(exchange)) {
            return;
        }

        // A non-simple content type cannot be sent cross-origin without a preflight.
        var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            sendJson(exchange, 415, ApiResponse.failure(requestId(exchange), null,
                    "INVALID_REQUEST", "Content-Type must be application/json"));
            return;
        }

        ApiRequest request;
        try {
            request = gson.fromJson(readBody(exchange), ApiRequest.class);
            if (request == null || request.getCommand() == null || request.getCommand().trim().isEmpty()) {
                throw new ApiCommandException("INVALID_REQUEST", "A command name is required");
            }
        } catch (ApiCommandException | JsonParseException e) {
            sendJson(exchange, 400, ApiResponse.failure(
                    requestId(exchange), null, "INVALID_REQUEST", "Request JSON is invalid"));
            return;
        }

        var id = request.getRequestId() == null ? requestId(exchange) : request.getRequestId();

        // Handler threads are pooled and reused, so a stale mark from the previous request would
        // otherwise be reported as this one's.
        CaptureClock.clear();
        try {
            var result = commands.execute(request.getCommand(), request.getParams());
            sendCommandSuccess(exchange, id, request.getCommand(), result);
        } catch (Exception e) {
            sendFailure(exchange, id, request.getCommand(), e);
        }
    }

    // -- plumbing ------------------------------------------------------------------------------

    public boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        sendJson(exchange, 405, ApiResponse.failure(
                requestId(exchange), null, "INVALID_REQUEST", "Method not allowed"));
        return false;
    }

    private String readBody(HttpExchange exchange) throws IOException, ApiCommandException {
        try (var input = exchange.getRequestBody();
             var output = new ByteArrayOutputStream()) {
            var buffer = new byte[4096];
            var total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new ApiCommandException("INVALID_REQUEST", "Request body is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Unwraps to the first {@link ApiCommandException} in the cause chain so a coded failure thrown
     * behind a bridge's {@code ExecutionException} still reports its own code.
     */
    private void sendFailure(HttpExchange exchange, String id, String command, Throwable error)
            throws IOException {
        var cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) {
            if (cause instanceof ApiCommandException) {
                break;
            }
            cause = cause.getCause();
        }

        String code;
        if (cause instanceof ApiCommandException) {
            code = ((ApiCommandException) cause).getCode();
        } else if (cause instanceof IllegalArgumentException) {
            code = "INVALID_ARGUMENT";
        } else {
            code = "INTERNAL_ERROR";
        }

        String message;
        if (cause instanceof ApiCommandException) {
            message = cause.getMessage();
        } else if ("INVALID_ARGUMENT".equals(code)) {
            // These messages are authored here ("Configuration key is unknown or protected",
            // "Value is outside the configured range"), not derived from client state, so passing
            // them through leaks nothing and is the difference between a caller being able to fix
            // the request and having to guess. easy-rl genericizes them; that costs too much here.
            message = cause.getMessage() == null ? "An argument was invalid" : cause.getMessage();
        } else {
            // Deliberately generic: the detail goes to the log, not to the wire.
            message = "The command could not be completed";
            log.warn("[control-api] command '{}' failed", command, error);
        }

        sendJson(exchange, "INTERNAL_ERROR".equals(code) ? 500 : 400,
                ApiResponse.failure(id == null ? requestId(exchange) : id, command, code, message));
    }

    public void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        writeBytes(exchange, status, gson.toJson(body).getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8", true);
    }

    private void sendCommandSuccess(HttpExchange exchange, String id, String command, Object result)
            throws IOException {
        var bytes = gson.toJson(ApiResponse.success(id, command, result, CaptureClock.taken()))
                .getBytes(StandardCharsets.UTF_8);

        if (bytes.length > maximumResponseBytes) {
            bytes = gson.toJson(ApiResponse.failure(id, command, "RESULT_TOO_LARGE",
                            "The serialized result exceeds the " + (maximumResponseBytes / (1024 * 1024))
                                    + " MiB response limit"))
                    .getBytes(StandardCharsets.UTF_8);
            writeBytes(exchange, 413, bytes, "application/json; charset=utf-8", true);
            return;
        }

        writeBytes(exchange, 200, bytes, "application/json; charset=utf-8", true);
    }

    /** Writes a response body, gzipping when the caller accepts it. No CORS headers, ever. */
    public void writeBytes(HttpExchange exchange, int status, byte[] uncompressed, String contentType,
                           boolean allowGzip) throws IOException {
        var bytes = uncompressed;
        var acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (allowGzip && acceptEncoding != null
                && acceptEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            var compressed = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(compressed)) {
                gzip.write(uncompressed);
            }
            bytes = compressed.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Vary", "Accept-Encoding");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * Streams events as SSE until the client disconnects, the stream is closed, or the server stops.
     * Holds this pool thread for the whole life of the stream - hence the concurrent-stream cap in
     * {@code PluginLogService}.
     */
    public void streamEvents(HttpExchange exchange, EventSource source) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        try (var output = exchange.getResponseBody()) {
            while (running.get() && !source.isClosed()) {
                String payload;
                try {
                    payload = source.next(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // A comment frame keeps intermediaries and the client from timing out a quiet stream.
                output.write((payload == null ? ": heartbeat\n\n" : payload)
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (payload != null && source.isTerminal()) {
                    break;
                }
            }
        } catch (IOException e) {
            // Client disconnected. Not an error.
        } finally {
            source.close();
            exchange.close();
        }
    }

    /** Adapts a queue of events to the SSE loop above. */
    public interface EventSource extends AutoCloseable {
        /** Next frame, already SSE-formatted, or null on timeout so a heartbeat goes out. */
        String next(long timeout, TimeUnit unit) throws InterruptedException;

        boolean isClosed();

        /** Whether the frame just returned ends the stream. */
        boolean isTerminal();

        @Override
        void close();
    }

    public static String requestId(HttpExchange exchange) {
        var id = exchange.getRequestHeaders().getFirst("X-Request-ID");
        return id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
    }

    public static String queryString(String query, String key) {
        if (query == null) {
            return null;
        }
        for (var part : query.split("&")) {
            var pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    public static int queryInteger(String query, String key, int fallback, int minimum, int maximum) {
        if (query == null) {
            return fallback;
        }
        for (var part : query.split("&")) {
            var pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                try {
                    return Math.max(minimum, Math.min(maximum, Integer.parseInt(pair[1])));
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    public static long headerLong(HttpExchange exchange, String name, long fallback) {
        var value = exchange.getRequestHeaders().getFirst(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            // Interrupts the SSE handlers blocked in poll() rather than waiting out a heartbeat.
            executor.shutdownNow();
            executor = null;
        }
    }
}

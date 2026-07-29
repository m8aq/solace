package net.solace.loader.controlapi.log;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.RequiredArgsConstructor;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.ApiResponse;
import net.solace.loader.controlapi.ApiServer;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/plugins/{pluginId}/logs/stream} - server-sent events for one plugin, or for the
 * {@link PluginLogService#ROOT_ID} pseudo-plugin to get everything.
 *
 * <p>Query: {@code tail=N} replays the last N buffered events, {@code requireRunning=false} allows
 * reading a stopped plugin's tail. The {@code Last-Event-ID} header resumes after a dropped
 * connection.
 */
@RequiredArgsConstructor
public final class PluginLogRoute implements HttpHandler {
    public static final String PATH = "/api/plugins";

    private static final String PREFIX = "/api/plugins/";
    private static final String SUFFIX = "/logs/stream";
    private static final int MAX_TAIL = 500;

    private final ApiServer server;
    private final PluginLogService logs;
    private final Gson gson;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!server.method(exchange, "GET") || !server.authorize(exchange)) {
            return;
        }

        var path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) {
            server.sendJson(exchange, 404, ApiResponse.failure(
                    ApiServer.requestId(exchange), null, "COMMAND_NOT_FOUND", "Endpoint not found"));
            return;
        }

        var encoded = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        var pluginId = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
        var query = exchange.getRequestURI().getRawQuery();
        var tail = ApiServer.queryInteger(query, "tail", 0, 0, MAX_TAIL);
        var requireRunning = !"false".equals(queryString(query, "requireRunning"));
        var lastEventId = ApiServer.headerLong(exchange, "Last-Event-ID", -1L);

        PluginLogStream stream;
        try {
            stream = logs.subscribe(pluginId, tail, lastEventId, requireRunning);
        } catch (ApiCommandException e) {
            var status = "TOO_MANY_STREAMS".equals(e.getCode()) ? 429 : 409;
            server.sendJson(exchange, status, ApiResponse.failure(
                    ApiServer.requestId(exchange), "logs.stream", e.getCode(), e.getMessage()));
            return;
        }

        server.streamEvents(exchange, new StreamSource(stream, gson));
    }

    private static String queryString(String query, String key) {
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

    private static final class StreamSource implements ApiServer.EventSource {
        private final PluginLogStream stream;
        private final Gson gson;
        private boolean terminal;

        private StreamSource(PluginLogStream stream, Gson gson) {
            this.stream = stream;
            this.gson = gson;
        }

        @Override
        public String next(long timeout, TimeUnit unit) throws InterruptedException {
            var event = stream.poll(timeout, unit);
            if (event == null) {
                return null;
            }
            terminal = "plugin-stopped".equals(event.getEvent());
            return "event: " + event.getEvent() + "\n"
                    + "id: " + event.getSequence() + "\n"
                    + "data: " + gson.toJson(event) + "\n\n";
        }

        @Override
        public boolean isClosed() {
            return stream.isClosed();
        }

        @Override
        public boolean isTerminal() {
            return terminal;
        }

        @Override
        public void close() {
            stream.close();
        }
    }
}

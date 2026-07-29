package net.solace.loader.controlapi.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.RequiredArgsConstructor;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.ApiResponse;
import net.solace.loader.controlapi.ApiServer;

import java.io.IOException;

/**
 * {@code GET /api/screenshot} - the PNG as binary.
 *
 * <p>Exists alongside the {@code screenshot} command because base64 inside a JSON envelope inflates a
 * full-size frame by about a third, and {@code curl -o shot.png} is the natural way to grab one.
 *
 * <p>Query: {@code timeoutMs}, {@code maxWidth}, {@code include} ({@code canvas} by default, or
 * {@code frame} for the whole window including the plugin panel and sidebar).
 */
@RequiredArgsConstructor
public final class ScreenshotRoute implements HttpHandler {
    public static final String PATH = "/api/screenshot";

    private final ApiServer server;
    private final ScreenshotCommandService screenshots;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!server.method(exchange, "GET") || !server.authorize(exchange)) {
            return;
        }

        var query = exchange.getRequestURI().getRawQuery();
        var timeout = ApiServer.queryInteger(query, "timeoutMs", 0, 0, 60_000);
        var maxWidth = ApiServer.queryInteger(query, "maxWidth", 0, 0, 8192);
        var includeFrame = "frame".equals(ApiServer.queryString(query, "include"));

        try {
            var capture = screenshots.capture(timeout, maxWidth, includeFrame);
            // Already compressed; gzipping a PNG buys nothing and costs CPU on a large frame.
            server.writeBytes(exchange, 200, capture.getPng(), "image/png", false);
        } catch (ApiCommandException e) {
            server.sendJson(exchange, "SCREENSHOT_TIMEOUT".equals(e.getCode()) ? 504 : 500,
                    ApiResponse.failure(ApiServer.requestId(exchange), "screenshot",
                            e.getCode(), e.getMessage()));
        } catch (Exception e) {
            server.sendJson(exchange, 500, ApiResponse.failure(ApiServer.requestId(exchange),
                    "screenshot", "INTERNAL_ERROR", "Screenshot failed"));
        }
    }
}

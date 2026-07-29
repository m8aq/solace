package net.solace.loader.controlapi.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import net.solace.loader.controlapi.ApiCommandException;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Captures the client canvas as a PNG, or the whole window when {@code includeFrame} is set.
 *
 * <p>This is what makes "drive it without looking at the window" workable - the operator can leave
 * the client unfocused or on another Space and still see exactly what the client sees.
 *
 * <p><b>Requires a rendering window.</b> {@link DrawManager#requestNextFrameListener} only fires from
 * the client's draw callback, so if no frame is produced the future never completes. Unfocused and
 * backgrounded are fine; <em>minimized</em> is not - on macOS an iconified frame can stop repainting
 * entirely. Hence the mandatory timeout and an error message that says so rather than hanging.
 */
@RequiredArgsConstructor
public final class ScreenshotCommandService {
    private final long defaultTimeoutMillis;

    /**
     * Resolved from RuneLite's own injector rather than {@code Static.injector}. {@code DrawManager}
     * is a {@code @Singleton} with no explicit binding - a just-in-time one - and this is provably the
     * same instance {@code Hooks} was constructed with, which is the one the render loop calls.
     * Resolving it from the child injector relies on subtler Guice JIT-ancestor behaviour for an
     * object whose identity is load-bearing.
     */
    private static DrawManager drawManager() {
        return RuneLite.getInjector().getInstance(DrawManager.class);
    }

    public Capture capture(long timeoutMillis, int maxWidth) throws Exception {
        return capture(timeoutMillis, maxWidth, false);
    }

    public Capture capture(long timeoutMillis, int maxWidth, boolean includeFrame) throws Exception {
        var frame = new CompletableFuture<Image>();

        // DrawManager keeps listeners in a CopyOnWriteArrayList, so registering from an HTTP pool
        // thread is safe. The callback runs on the client thread and must do nothing but hand the
        // image over - all encoding happens back here.
        drawManager().requestNextFrameListener(frame::complete);

        Image image;
        try {
            image = frame.get(timeoutMillis <= 0 ? defaultTimeoutMillis : timeoutMillis,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiCommandException("SCREENSHOT_TIMEOUT",
                    "The client rendered no frame within " + timeoutMillis + " ms. The window must be "
                            + "open and not minimized; unfocused or on another desktop is fine.");
        } catch (ExecutionException e) {
            throw new ApiCommandException("INTERNAL_ERROR", "Frame capture failed");
        }

        var buffered = image instanceof BufferedImage
                ? (BufferedImage) image
                : ImageUtil.bufferedImageFromImage(image);

        if (includeFrame) {
            buffered = compositeOntoFrame(buffered);
        }

        if (maxWidth > 0 && buffered.getWidth() > maxWidth) {
            var height = Math.max(1, buffered.getHeight() * maxWidth / buffered.getWidth());
            buffered = ImageUtil.resizeImage(buffered, maxWidth, height);
        }

        return new Capture(encode(buffered), buffered.getWidth(), buffered.getHeight());
    }

    /** The base64 form, for callers that only speak {@code POST /api/command}. */
    public Map<String, Object> captureAsJson(long timeoutMillis, int maxWidth) throws Exception {
        return captureAsJson(timeoutMillis, maxWidth, false);
    }

    public Map<String, Object> captureAsJson(long timeoutMillis, int maxWidth, boolean includeFrame)
            throws Exception {
        var capture = capture(timeoutMillis, maxWidth, includeFrame);
        var result = new LinkedHashMap<String, Object>();
        result.put("width", capture.getWidth());
        result.put("height", capture.getHeight());
        result.put("include", includeFrame ? "frame" : "canvas");
        result.put("format", "png");
        result.put("encoding", "base64");
        result.put("bytes", capture.getPng().length);
        result.put("data", Base64.getEncoder().encodeToString(capture.getPng()));
        result.put("capturedAt", Instant.now().toString());
        return result;
    }

    /**
     * Paints the whole client window -- plugin panel, sidebar, title bar -- and draws the freshly
     * captured game frame into it at the canvas's position.
     *
     * <p>The two halves cannot come from one source. {@code ClientUI.paint} walks the Swing hierarchy,
     * which is the only way to get the chrome, but the game canvas is a heavyweight surface: under the
     * GPU plugin it is drawn by the driver, so a Swing paint pass reproduces it blank or stale. The
     * {@code DrawManager} frame is authoritative for the game and useless for everything else. Hence
     * the composite.
     *
     * <p>Runs on the EDT because {@code ClientUI.paint} asserts it. If anything about the window is
     * unavailable the canvas image is returned unchanged -- a game-only screenshot beats an error.
     */
    private static BufferedImage compositeOntoFrame(BufferedImage canvas) throws Exception {
        var ui = RuneLite.getInjector().getInstance(ClientUI.class);

        var width = ui.getWidth();
        var height = ui.getHeight();
        if (width <= 0 || height <= 0) {
            return canvas;
        }

        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var failure = new AtomicReference<Throwable>();

        Runnable paint = () -> {
            var g = result.createGraphics();
            try {
                ui.paint(g);

                var offset = ui.getCanvasOffset();
                // Stretched mode and HiDPI both make the rendered frame a different size from the
                // canvas component, so scale to the component's bounds rather than blitting 1:1.
                var bounds = canvasBounds();
                if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                    g.drawImage(canvas, offset.x, offset.y, bounds.width, bounds.height, null);
                } else {
                    g.drawImage(canvas, offset.x, offset.y, null);
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                g.dispose();
            }
        };

        // Callers are HTTP pool threads, but invokeAndWait deadlocks if we are already on the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            paint.run();
        } else {
            SwingUtilities.invokeAndWait(paint);
        }

        if (failure.get() != null) {
            throw new ApiCommandException("INTERNAL_ERROR",
                    "Could not paint the client window: " + failure.get().getMessage());
        }

        return result;
    }

    /** The canvas component's size, or null if the client is not far enough up to have one. */
    private static Dimension canvasBounds() {
        try {
            var canvas = RuneLite.getInjector().getInstance(Client.class).getCanvas();
            return canvas == null ? null : canvas.getSize();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static final class Capture {
        private final byte[] png;
        private final int width;
        private final int height;
    }
}

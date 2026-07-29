package net.solace.loader.controlapi.thread;

import lombok.RequiredArgsConstructor;
import net.solace.loader.controlapi.ApiCommandException;

import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Marshals a command onto the Swing event dispatch thread.
 *
 * <p>Mandatory for anything touching plugin lifecycle: {@code PluginManagerImpl.startPlugin} and
 * {@code stopPlugin} both {@code assert SwingUtilities.isEventDispatchThread()}, and assertions are
 * off unless the JVM was started with {@code -ea}. Without this bridge a violation from an HTTP pool
 * thread would not throw - it would silently race the Swing config panel and corrupt the active
 * plugin list.
 *
 * <p>{@code SwingUtil.syncExec} is not a substitute: it has no timeout, so a wedged EDT would hang
 * the HTTP handler thread forever.
 */
@RequiredArgsConstructor
public final class EdtBridge {
    private final long timeoutMillis;

    public <T> T call(Supplier<T> supplier) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return supplier.get();
        }

        var future = new CompletableFuture<T>();
        SwingUtilities.invokeLater(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiCommandException("EDT_TIMEOUT",
                    "Event dispatch thread did not respond within " + timeoutMillis + " ms");
        }
    }
}

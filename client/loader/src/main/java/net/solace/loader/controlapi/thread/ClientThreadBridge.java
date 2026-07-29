package net.solace.loader.controlapi.thread;

import lombok.RequiredArgsConstructor;
import net.runelite.client.callback.ClientThread;
import net.solace.api.Static;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.CaptureClock;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Marshals a command onto the client thread and waits for the result.
 *
 * <p>Deliberately not {@code net.solace.api.domain.game.IClientThread}. That implementation has two
 * defects an off-thread API server cannot live with: its timeout is hardcoded to 1000 ms, and its
 * {@code invokeAndWait} catch block logs without returning, so a callable that throws while already
 * on the client thread falls through and runs a <em>second</em> time via the {@code FutureTask} path.
 * Both are tracked as separate fixes.
 */
@RequiredArgsConstructor
public final class ClientThreadBridge {
    private final ClientThread clientThread;
    private final long timeoutMillis;

    public <T> T call(Callable<T> supplier) throws Exception {
        // Re-entrancy guard: waiting on the client thread from the client thread would deadlock
        // until the timeout. easy-rl has no equivalent because its commands never nest.
        if (Static.getClient().isClientThread()) {
            var value = supplier.call();
            CaptureClock.mark(System.currentTimeMillis());
            return value;
        }

        var future = new CompletableFuture<T>();

        // Written on the client thread, read here only after future.get() has established a
        // happens-before edge with the completing thread.
        var capturedAt = new long[1];

        clientThread.invokeLater(() -> {
            try {
                capturedAt[0] = System.currentTimeMillis();
                future.complete(supplier.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            var value = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            CaptureClock.mark(capturedAt[0]);
            return value;
        } catch (TimeoutException e) {
            throw new ApiCommandException("CLIENT_THREAD_TIMEOUT",
                    "Client thread did not respond within " + timeoutMillis + " ms");
        }
    }
}

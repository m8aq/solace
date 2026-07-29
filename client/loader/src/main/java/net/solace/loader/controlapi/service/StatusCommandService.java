package net.solace.loader.controlapi.service;

import lombok.RequiredArgsConstructor;
import net.solace.api.Static;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Backs {@code client.status}.
 *
 * <p>Deliberately never blocks on the client thread. easy-rl routes its status command through the
 * client-thread bridge, so when the client thread is wedged - exactly the situation where status is
 * the thing you need - status times out and tells you nothing. Here the game-state and world reads
 * happen off-thread (they are plain field reads on RuneLite's injected client) and client-thread
 * health is reported as a separate, bounded probe.
 */
@RequiredArgsConstructor
public final class StatusCommandService {
    private static final long PROBE_TIMEOUT_MILLIS = 250;

    private final IntSupplier port;
    private final IntSupplier commandCount;
    private final Instant startedAt;

    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();

        var properties = Static.getSolaceProperties();
        result.put("solaceVersion", properties.getLoaderVersion());
        result.put("runeLiteVersion", properties.getRuneLiteVersion());
        result.put("commitHash", properties.getCommitHash());
        result.put("buildDate", properties.getBuildDate());

        var client = Static.getClient();
        var gameState = client.getGameState();
        result.put("gameState", gameState == null ? null : gameState.name());
        result.put("loggedIn", gameState != null && "LOGGED_IN".equals(gameState.name()));
        result.put("world", client.getWorld());

        var probe = probeClientThread();
        result.put("clientThreadHealthy", probe >= 0);
        result.put("clientThreadLatencyMs", probe < 0 ? null : probe);

        result.put("bindAddress", "127.0.0.1");
        result.put("port", port.getAsInt());
        result.put("commandCount", commandCount.getAsInt());
        result.put("startedAt", startedAt.toString());

        return result;
    }

    /**
     * Round-trip time to the client thread in millis, or -1 if it did not answer within
     * {@value #PROBE_TIMEOUT_MILLIS} ms. Uses {@code invokeLater} rather than a blocking bridge so a
     * wedged client thread costs the caller a quarter second, not the whole command timeout.
     */
    private long probeClientThread() {
        var latch = new CountDownLatch(1);
        var start = System.nanoTime();
        try {
            Static.getClientThread().invokeLater(latch::countDown);
            if (!latch.await(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return -1;
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}

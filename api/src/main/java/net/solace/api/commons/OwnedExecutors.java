package net.solace.api.commons;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executors created by Solace code, tracked so they can all be shut down together.
 *
 * <p>Exists because of hot reload. A {@code private static final Executor} initialised at class-load
 * creates a non-daemon thread that nothing ever stops - and on a reload the thread outlives its
 * generation, holding a reference to a class from the outgoing classloader and pinning that whole
 * generation in Metaspace forever. One such executor is enough to make reloading leak without bound.
 *
 * <p>Threads are named after their owner so a stack dump attributes them, and marked daemon so they
 * can never keep the JVM alive on their own.
 */
public final class OwnedExecutors {
    private static final List<ExecutorService> TRACKED = new CopyOnWriteArrayList<>();

    private OwnedExecutors() {
    }

    /** A single-threaded daemon executor, tracked for shutdown. */
    public static ExecutorService singleThread(String name) {
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "solace-" + name);
            thread.setDaemon(true);
            return thread;
        });
        TRACKED.add(executor);
        return executor;
    }

    /**
     * Stops every tracked executor. Called during teardown; safe to call more than once, and safe to
     * call when nothing was ever created.
     */
    public static void shutdownAll() {
        for (var executor : TRACKED) {
            try {
                executor.shutdownNow();
            } catch (RuntimeException e) {
                // A failing executor must not stop the rest of teardown.
            }
        }
        TRACKED.clear();
    }

    public static int trackedCount() {
        return TRACKED.size();
    }
}

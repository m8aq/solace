package net.solace.boot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Swaps the Solace layer for a freshly built one without restarting the client.
 *
 * <p>Sequence: preflight a candidate loader → tear the current generation down → close its loader →
 * bring the candidate up. RuneLite, the canvas and the game session are untouched throughout, because
 * they live on the application classloader above this.
 *
 * <p><b>Failure policy is preflight hard, then fail forward.</b> Re-injecting the outgoing generation
 * after a failure is not merely awkward, it is unsound: {@code requestStaticInjection(Static.class)}
 * would run a second time against a new injector while every already-initialised SDK facade still
 * holds the first injector's objects in its {@code private static final} cache - two disjoint object
 * graphs behind one {@code Static}, failing much later and nowhere near the cause. So a failure after
 * teardown leaves the client running, logged in and Solace-less, and the next successful build
 * recovers it.
 */
public final class LayerReloader {
    /** Loaded during preflight to prove the candidate is viable. Deliberately no SDK class here -
     *  initialising a facade before {@code Static.injector} is assigned caches a null for good. */
    private static final String[] SENTINELS = {
            "net.solace.loader.DevEntry",
            "net.solace.api.Static",
            "net.solace.impl.ApiModule",
            "net.solace.loader.SolaceInitializer",
            "net.solace.ui.module.UiModule",
            // :bundled has no module class, so name a plugin. loadClass does not run static
            // initialisers, so this cannot trip the facade-caching hazard above.
            "net.solace.loader.plugins.autologin.SolaceAutoLoginPlugin",
    };

    /** Retained generations before the operator is told to restart. */
    private static final int RETAINED_WARN_THRESHOLD = 40;

    /** One reload at a time - the watcher thread and an API request can both ask at once. */
    private final Semaphore gate = new Semaphore(1);
    private final AtomicInteger generation = new AtomicInteger();
    private final List<WeakReference<ClassLoader>> pastGenerations = new ArrayList<>();
    private final ClassLoader app;

    private volatile ClassLoader current;
    private volatile String state = "starting";
    private volatile String lastError;
    private volatile Instant loadedAt;
    private volatile long lastReloadMillis;
    private volatile long lastMetaspaceBytes;
    private volatile List<String> lastLeaks = List.of();

    public LayerReloader(ClassLoader app) {
        this.app = app;
    }

    /** Records the generation the launcher built, so a later reload knows what to tear down. */
    public void adopt(ClassLoader loader) {
        current = loader;
        generation.set(1);
        state = "running";
        loadedAt = Instant.now();
        lastMetaspaceBytes = metaspaceUsed();
    }

    /** The live generation's loader, or null when the layer failed to start. */
    public ClassLoader currentLoader() {
        return current;
    }

    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("generation", generation.get());
        result.put("state", state);
        result.put("loadedAt", loadedAt == null ? null : loadedAt.toString());
        result.put("lastReloadMillis", lastReloadMillis);
        result.put("retainedGenerations", countRetained());
        result.put("metaspaceUsedBytes", metaspaceUsed());
        result.put("lastError", lastError);
        result.put("leaks", lastLeaks);
        return result;
    }

    /**
     * Reloads the layer. Blocks until done; must not be called from the EDT (teardown's quiesce loop
     * sleeps for seconds) nor from a thread the layer owns (teardown would interrupt its own caller).
     */
    public Map<String, Object> reload(List<Path> jars) {
        if (!gate.tryAcquire()) {
            var busy = status();
            busy.put("reloaded", false);
            busy.put("reason", "a reload is already in progress");
            return busy;
        }

        var start = System.currentTimeMillis();
        try {
            return doReload(jars, start);
        } catch (Throwable e) {
            state = "failed";
            lastError = String.valueOf(e);
            System.err.println("[layer] reload failed");
            e.printStackTrace();
            var failed = status();
            failed.put("reloaded", false);
            return failed;
        } finally {
            gate.release();
        }
    }

    private Map<String, Object> doReload(List<Path> jars, long start) throws Exception {
        var next = generation.get() + 1;

        // Preflight first: a jar that failed to build, went missing, or is corrupt is caught here at
        // zero cost, with the running generation completely untouched.
        SolaceLayerClassLoader candidate;
        try {
            candidate = SolaceLayerClassLoader.open(jars, next, app);
            for (var sentinel : SENTINELS) {
                candidate.loadClass(sentinel);
            }
        } catch (Throwable e) {
            lastError = "preflight: " + e;
            System.out.println("[layer] preflight rejected the candidate: " + e);
            var rejected = status();
            rejected.put("reloaded", false);
            return rejected;
        }
        System.out.println("[layer] preflight ok, reloading (gen " + generation.get() + " -> " + next + ")");

        var outgoing = current;
        if (outgoing != null) {
            state = "unloading";
            var report = SolaceLayerLauncher.stopLayer(outgoing);
            System.out.println("[layer] teardown: " + report);

            // Between teardown and closing the loader: anything still pointing at the outgoing
            // generation is named here, and cleaned up where a public API allows.
            lastLeaks = LeakReport.run(outgoing);
            for (var leak : lastLeaks) {
                System.out.println("[layer] LEAK " + leak);
            }
        }

        state = "starting";
        try {
            SolaceLayerLauncher.startLayer(candidate, false);
        } catch (Throwable e) {
            // Past the point of no return. Leave the client alive rather than resurrecting a used
            // generation - see the class javadoc for why that would be unsound.
            state = "failed";
            // Unwrapped: the call crosses the classloader boundary reflectively, so the raw exception
            // is always InvocationTargetException, which tells you nothing about what actually broke.
            lastError = "start: " + rootCause(e);
            current = null;
            closeQuietly(candidate);
            System.err.println("[layer] the new generation failed to start; the client is running "
                    + "without Solace. Fix the code and rebuild - the watcher will recover.");
            e.printStackTrace();
            throw e;
        }

        if (outgoing != null) {
            pastGenerations.add(new WeakReference<>(outgoing));
            closeQuietly(outgoing);
        }

        // Teardown shut the RxJava schedulers down to release the threads whose captured security
        // context pinned the outgoing generation. Restart them from here, on a bootstrap thread, so
        // the replacements inherit the application classloader's context instead of a layer's.
        restartRxSchedulers();

        current = candidate;
        generation.set(next);
        state = "running";
        loadedAt = Instant.now();
        lastError = null;
        lastReloadMillis = System.currentTimeMillis() - start;

        reportMetaspace(next);

        var result = status();
        result.put("reloaded", true);
        return result;
    }

    /**
     * A deliberate {@code System.gc()} so weak references clear and the retained-generation count is
     * meaningful. Dev-only, and the single most legible signal that teardown missed something: if this
     * climbs by one per reload, a leak is pinning each generation.
     */
    private void reportMetaspace(int generationNumber) {
        System.gc();
        var used = metaspaceUsed();
        var delta = used - lastMetaspaceBytes;
        lastMetaspaceBytes = used;
        var retained = countRetained();

        System.out.printf("[layer] generation %d up in %d ms | retained %d | metaspace %d MiB (%+d MiB)%n",
                generationNumber, lastReloadMillis, retained,
                used / (1024 * 1024), delta / (1024 * 1024));

        // Every generation currently retains roughly 5 MiB. The remaining pin has not been identified
        // - it survives unregistering every EventBus subscriber, removing overlays, nav buttons,
        // scheduled methods and input listeners, and flushing the Introspector and Guice annotation
        // caches. Restarting the client is the mitigation, so say so before Metaspace runs out rather
        // than letting it fail as an opaque OutOfMemoryError.
        if (retained >= RETAINED_WARN_THRESHOLD) {
            System.out.printf("[layer] WARNING: %d generations retained (~%d MiB). Reloading leaks "
                            + "about 5 MiB each; restart the client when convenient.%n",
                    retained, used / (1024 * 1024));
        }
    }

    private int countRetained() {
        var alive = 0;
        for (var ref : pastGenerations) {
            if (ref.get() != null) {
                alive++;
            }
        }
        return alive;
    }

    private static Throwable rootCause(Throwable e) {
        var cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static long metaspaceUsed() {
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(pool.getName())) {
                return pool.getUsage().getUsed();
            }
        }
        return -1;
    }

    private static void closeQuietly(ClassLoader loader) {
        if (loader instanceof SolaceLayerClassLoader) {
            try {
                ((SolaceLayerClassLoader) loader).close();
            } catch (IOException e) {
                System.err.println("[layer] failed to close a loader: " + e);
            }
        }
    }

    private static void restartRxSchedulers() {
        try {
            io.reactivex.rxjava3.schedulers.Schedulers.start();
        } catch (Throwable e) {
            System.err.println("[layer] could not restart RxJava schedulers: " + e);
        }
    }
}

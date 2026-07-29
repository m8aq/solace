package net.solace.loader;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.solace.api.Static;
import net.solace.loader.hotswap.DevPluginHotSwapHolder;
import net.solace.loader.hotswap.DevPluginHotSwapService;
import net.solace.loader.plugins.PluginManagerImpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single entry point a hot-reload bootstrap uses to drive the Solace layer.
 *
 * <p>Exists so the bootstrap needs to know exactly one class name and can talk to it entirely through
 * JDK types - {@code boolean}, {@code Map}, {@code Runnable}, {@code List} - which resolve to the same
 * {@code Class} on both sides of the classloader boundary. Anything richer would require the bootstrap
 * to load a Solace type, which would put a second copy of it on the app classloader and defeat the
 * whole design.
 *
 * <p><b>Ordering matters here.</b> The 44 SDK facades cache {@code Static.getX()} in a
 * {@code private static final} at class-load, so {@code Static.injector} must be assigned before any
 * of them initialises. {@code SolaceLoader.start} guarantees that (Guice's static injection runs
 * during {@code createChildInjector}), which is why {@code startHotSwap} - whose preflight loads
 * plugin classes - must come after it and not before.
 */
@Slf4j
public final class DevEntry {
    private static volatile Runnable reloadRequest;

    private DevEntry() {
    }

    /**
     * Brings the layer up.
     *
     * @param firstInit false on a reload. Suppresses steps that mutate RuneLite state which survives
     *                  the reload - see {@code SolaceInitializer.start(boolean)}.
     */
    public static void start(boolean firstInit) throws Exception {
        var loaderModule = new net.solace.loader.modules.LoaderModule();
        var injector = Static.injector = RuneLite.getInjector().createChildInjector(
                new net.solace.impl.ApiModule(),
                loaderModule,
                new net.solace.loader.modules.InteractionModule(),
                new net.solace.loader.modules.ExternalPluginsModule(loaderModule.getScript()),
                new net.solace.ui.module.UiModule());

        injector.getInstance(SolaceManager.class).start(firstInit);

        startHotSwap();
    }

    /**
     * Runs {@link #stop()} on a dedicated thread and waits for it.
     *
     * <p>Teardown must never run on a thread it is going to shut down. The control API's server is a
     * plugin, so {@code stopPlugins()} closes it, and {@code ApiServer.close()} calls
     * {@code shutdownNow()} on its own pool - which interrupts the caller mid-teardown if the caller
     * is one of those threads. Same argument for any plugin thread. The Swing EDT is excluded for the
     * opposite reason: the quiesce loop sleeps for seconds and would freeze the client.
     */
    public static Map<String, Object> stopDetached() throws InterruptedException {
        var result = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var thread = new Thread(() -> result.set(stop()), "solace-layer-teardown");
        thread.setDaemon(true);
        thread.start();
        thread.join();
        return result.get();
    }

    /**
     * Tears the layer down. Never throws - a teardown that aborts partway leaves the client in an
     * indeterminate state, so failures are collected into the report instead.
     *
     * <p>Do not call this from a thread teardown will stop; use {@link #stopDetached()}.
     */
    public static Map<String, Object> stop() {
        var report = new LinkedHashMap<String, Object>();
        List<String> failures;
        try {
            var manager = Static.injector.getInstance(SolaceManager.class);
            failures = manager.unload();
        } catch (Throwable e) {
            log.error("Teardown failed catastrophically", e);
            failures = List.of("unload: " + e);
        }
        report.put("failures", failures);
        report.put("clean", failures.isEmpty());
        return report;
    }

    /** Registers the bootstrap's reload trigger, so the control API can ask for one. */
    public static void setReloadRequest(Runnable request) {
        reloadRequest = request;
    }

    /** Null when running without a reload-capable bootstrap. */
    public static Runnable getReloadRequest() {
        return reloadRequest;
    }

    private static void startHotSwap() {
        var manager = Static.getPluginManager();
        if (!(manager instanceof PluginManagerImpl)) {
            return;
        }
        var hotSwap = DevPluginHotSwapService.createIfConfigured((PluginManagerImpl) manager);
        if (hotSwap == null) {
            return;
        }
        DevPluginHotSwapHolder.set(hotSwap);
        try {
            hotSwap.start();
        } catch (RuntimeException e) {
            log.error("[hotswap] failed to start", e);
        }
    }
}

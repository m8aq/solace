package net.solace.boot;

import net.runelite.client.RuneLite;

import javax.swing.JOptionPane;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Development entry point that runs Solace in a reloadable classloader.
 *
 * <p>Boots RuneLite on the application classloader, then brings the Solace layer up in a child loader
 * over the jars listed in {@code -Dsolace.layer.path}. Everything here is on the app loader and
 * therefore <b>not</b> reloadable - changing {@code net.solace.boot.*} needs a restart.
 *
 * <p>Production is untouched: {@code SolaceLauncher} still launches flat, on a single classloader.
 */
public final class SolaceLayerLauncher {
    public static final String LAYER_PATH_PROPERTY = "solace.layer.path";

    /** The one layer class the bootstrap is allowed to name. See {@code DevEntry}. */
    private static final String ENTRY_CLASS = "net.solace.loader.DevEntry";

    /** Loading this from the app loader must fail - see {@link #assertLayerIsolated()}. */
    private static final String SENTINEL_CLASS = "net.solace.api.Static";

    private SolaceLayerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        RuneLite.main(new String[]{"--debug", "--developer-mode"});

        assertLayerIsolated();

        var app = SolaceLayerLauncher.class.getClassLoader();
        prewarmSharedThreadPools();

        var jars = layerJars();
        var loader = SolaceLayerClassLoader.open(jars, 1, app);
        System.out.println("[layer] generation 1 over " + jars.size() + " jars");

        var reloader = new LayerReloader(app);
        setReloadRequest(() -> reloader.reload(layerJars()));

        startLayer(loader, true);
        reloader.adopt(loader);

        // The shutdown hook lives HERE, not in the layer. ApplicationShutdownHooks.hooks is a static
        // JDK map that lives for the life of the JVM, so a hook holding a layer class would pin that
        // generation forever - one line was enough to make every generation uncollectable.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stopLayer(reloader.currentLoader());
            } catch (Throwable ignored) {
                // Shutting down anyway.
            }
        }, "solace-layer-shutdown"));

        var server = new ReloadServer(reloader, SolaceLayerLauncher::layerJars);
        server.start();

        var watcher = new LayerWatcher(
                jarList -> Boolean.TRUE.equals(reloader.reload(jarList).get("reloaded")));
        watcher.start();
    }

    /**
     * Brings a generation up by calling {@code DevEntry.start(firstInit)} across the loader boundary.
     *
     * <p>Only ever names {@link #ENTRY_CLASS}, and only passes JDK types. Touching any other layer
     * class from here is a real hazard rather than untidiness: the 44 SDK facades cache
     * {@code Static.getX()} in a {@code private static final} at class-load, so initialising one
     * before {@code DevEntry.start} has assigned {@code Static.injector} caches a null service for the
     * life of that generation, with no error at the point of failure.
     */
    static void startLayer(ClassLoader layer, boolean firstInit) throws Exception {
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(layer);
        try {
            var entry = layer.loadClass(ENTRY_CLASS);
            if (reloadRequest != null) {
                // Handed over before start(), so the control API can expose layer.reload as soon as
                // it comes up. A Runnable is a JDK type, so it is the same Class on both sides.
                entry.getMethod("setReloadRequest", Runnable.class).invoke(null, reloadRequest);
            }
            entry.getMethod("start", boolean.class).invoke(null, firstInit);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** The bootstrap's reload trigger, injected into each generation. */
    private static volatile Runnable reloadRequest;

    static void setReloadRequest(Runnable request) {
        reloadRequest = request;
    }

    /** Tears a generation down, returning its failure report. */
    static Object stopLayer(ClassLoader layer) throws Exception {
        var entry = layer.loadClass(ENTRY_CLASS);
        return entry.getMethod("stopDetached").invoke(null);
    }

    /**
     * Creates the process-wide shared thread pools from here, before any layer code runs.
     *
     * <p>This is the fix for the per-reload classloader leak, and the mechanism is not obvious. A
     * thread captures an {@link java.security.AccessControlContext} when it is created, and that
     * context holds the {@code ProtectionDomain} of every class on the creating stack - and a
     * {@code ProtectionDomain} references its {@code ClassLoader}. So a long-lived thread first
     * created while layer code was on the stack pins that entire generation through
     * {@code Thread.inheritedAccessControlContext}, for as long as the JVM runs.
     *
     * <p>Found by heap dump: the chain was
     * {@code RxThreadFactory$RxCustomThread -> AccessControlContext -> ProtectionDomain[] ->
     * ProtectionDomain -> SolaceLayerClassLoader}, rooted at the thread itself. Nothing in Solace
     * referenced the loader; the JVM's own security plumbing did. That is why enumerating event
     * listeners, overlays, schedulers and caches never found it.
     *
     * <p>Touching the schedulers here means their threads are born with the application loader's
     * context, which lives forever anyway, so nothing is pinned.
     */
    private static void prewarmSharedThreadPools() {
        try {
            io.reactivex.rxjava3.schedulers.Schedulers.io().scheduleDirect(() -> { });
            io.reactivex.rxjava3.schedulers.Schedulers.computation().scheduleDirect(() -> { });
            io.reactivex.rxjava3.schedulers.Schedulers.single().scheduleDirect(() -> { });
            io.reactivex.rxjava3.schedulers.Schedulers.newThread().scheduleDirect(() -> { });
        } catch (Throwable e) {
            System.err.println("[layer] could not pre-warm RxJava schedulers: " + e);
        }

        // Swing's shared owner frame is a JVM-wide singleton, created lazily the first time anything
        // opens a window or dialog without an explicit owner, and never released. It captures an
        // AccessControlContext at construction - so if layer code creates it first, that generation is
        // pinned for the life of the JVM by a JDK internal nobody can reach to clean up. Creating it
        // here means it belongs to the application loader instead.
        //
        // JOptionPane.getRootFrame() is the public way to force it; SwingUtilities' own accessor is
        // package-private.
        try {
            javax.swing.JOptionPane.getRootFrame();
        } catch (Throwable e) {
            System.err.println("[layer] could not pre-warm the Swing shared owner frame: " + e);
        }
    }

    /**
     * Fails loudly if any Solace class is reachable from the application classloader.
     *
     * <p>This is the design's single non-negotiable invariant, and it fails <em>silently</em> when
     * violated. {@code PluginManagerImpl.loadCorePlugins()} scans with Guava's {@code ClassPath},
     * whose scanner walks to the parent loader first - so a stray {@code bundled.jar} on
     * {@code java.class.path} makes every bundled plugin load from unreloadable classes, and nothing
     * reports an error. The symptom is simply that reloading appears to do nothing.
     */
    private static void assertLayerIsolated() {
        try {
            SolaceLayerLauncher.class.getClassLoader().loadClass(SENTINEL_CLASS);
        } catch (ClassNotFoundException expected) {
            return;
        }
        fail("The application classpath contains " + SENTINEL_CLASS + ".\n\n"
                + ":devboot must not depend on any Solace module - hot reload cannot work while a "
                + "second copy of the layer is reachable from the app classloader, and it fails "
                + "silently rather than throwing.", null);
    }

    /** The layer jars, in classpath order, from {@code -Dsolace.layer.path}. */
    static List<Path> layerJars() {
        var configured = System.getProperty(LAYER_PATH_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            fail("-D" + LAYER_PATH_PROPERTY + " is not set. Launch via :devboot:runDev or "
                    + "scripts/run-dev.sh.", null);
        }

        var jars = new ArrayList<Path>();
        for (var entry : configured.split(File.pathSeparator)) {
            if (entry.trim().isEmpty()) {
                continue;
            }
            var path = Paths.get(entry.trim()).toAbsolutePath();
            if (!Files.isRegularFile(path)) {
                fail("Layer jar is missing: " + path + "\n\nRun: ./gradlew layerJars", null);
            }
            jars.add(path);
        }
        if (jars.isEmpty()) {
            fail("-D" + LAYER_PATH_PROPERTY + " is empty.", null);
        }
        return jars;
    }

    static void fail(String message, Throwable cause) {
        System.err.println("[layer] FATAL: " + message);
        if (cause != null) {
            cause.printStackTrace();
        }
        try {
            JOptionPane.showMessageDialog(null, message, "Solace layer", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // Headless or no display - the stderr message is enough.
        }
        System.exit(1);
    }
}

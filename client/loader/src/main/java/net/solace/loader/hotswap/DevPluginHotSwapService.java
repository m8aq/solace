package net.solace.loader.hotswap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.solace.api.Static;
import net.solace.api.events.ExternalPluginsChanged;
import net.solace.api.plugins.LoopedPlugin;
import net.solace.api.plugins.Plugin;
import net.solace.api.plugins.PluginDescriptor;
import net.solace.loader.plugins.LoopedPluginManager;
import net.solace.loader.plugins.PluginManagerImpl;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.jar.JarFile;

/**
 * Reloads the {@code :devplugins} jar without restarting the client.
 *
 * <p>Adapted from easy-rl's {@code APIPluginHotSwapService}. The machinery ports; what gets reloaded
 * does not. easy-rl reloads a jar that includes its own API plugin; here the control API lives in
 * {@code :loader} on the app classloader and is <b>never</b> hot-swapped - it is the channel that
 * triggers and reports the swap, so reloading it would drop the HTTP listener mid-request and make a
 * failed reload unrecoverable.
 *
 * <p>Disabled entirely unless {@code -Dsolace.devplugins.jar} points at a real file, so production is
 * byte-identical.
 */
@Slf4j
public final class DevPluginHotSwapService implements AutoCloseable {
    public static final String JAR_PROPERTY = "solace.devplugins.jar";
    public static final String POLL_PROPERTY = "solace.devplugins.pollMillis";

    private static final String MANIFEST_ATTRIBUTE = "Solace-Plugin-Classes";
    private static final long DEFAULT_POLL_MILLIS = 750;
    private static final long QUIESCE_MILLIS = 3000;

    /** Serializes reloads: the watcher thread and an API call can both ask at once. */
    private static final Semaphore RELOAD_GATE = new Semaphore(1);

    private final Path jar;
    private final PluginManagerImpl pluginManager;

    private DevPluginJarWatcher watcher;
    private Generation current;

    @Getter
    private volatile String lastError;

    @Getter
    private volatile int generation;

    private DevPluginHotSwapService(Path jar, PluginManagerImpl pluginManager) {
        this.jar = jar;
        this.pluginManager = pluginManager;
    }

    /** Returns null when hot-swapping is not configured, which is the normal production case. */
    public static DevPluginHotSwapService createIfConfigured(PluginManagerImpl pluginManager) {
        var configured = System.getProperty(JAR_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        var path = Paths.get(configured.trim()).toAbsolutePath();
        log.info("[hotswap] dev plugin jar: {}", path);
        return new DevPluginHotSwapService(path, pluginManager);
    }

    public void start() {
        if (watcher != null) {
            return;
        }
        if (Files.isRegularFile(jar)) {
            reload(jar);
        }
        watcher = new DevPluginJarWatcher(jar, pollMillis(), this::reload);
        watcher.start();
    }

    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", true);
        result.put("jar", jar.toString());
        result.put("jarPresent", Files.isRegularFile(jar));
        result.put("generation", generation);
        result.put("loadedAt", current == null ? null : current.loadedAt.toString());
        result.put("pluginIds", current == null ? List.of() : current.pluginIds());
        result.put("lastError", lastError);
        return result;
    }

    /** Forces a reload regardless of whether the jar changed. */
    public Map<String, Object> reloadNow() {
        var ok = reload(jar);
        if (ok && watcher != null) {
            watcher.markAccepted();
        }
        var result = status();
        result.put("reloaded", ok);
        return result;
    }

    // -- the reload ----------------------------------------------------------------------------

    private boolean reload(Path source) {
        if (!RELOAD_GATE.tryAcquire()) {
            log.warn("[hotswap] a reload is already in progress");
            return false;
        }
        try {
            return doReload(source);
        } catch (Throwable e) {
            lastError = e.toString();
            log.error("[hotswap] reload failed", e);
            return false;
        } finally {
            RELOAD_GATE.release();
        }
    }

    private boolean doReload(Path source) throws Exception {
        Candidate candidate;
        try {
            candidate = preflight(source);
        } catch (NoDevPlugins e) {
            // plugins/dev is empty. Nothing to swap, and nothing wrong - report success so the
            // watcher accepts this build instead of retrying it as a rejected candidate.
            lastError = null;
            log.debug("[hotswap] no dev plugins declared; nothing to load");
            return true;
        } catch (Exception e) {
            lastError = "preflight: " + e.getMessage();
            log.warn("[hotswap] rejecting jar: {}", e.getMessage());
            return false;
        }

        var outgoing = current;
        var enabledStates = new HashMap<String, Boolean>();
        if (outgoing != null) {
            for (var plugin : outgoing.plugins) {
                enabledStates.put(plugin.getClass().getName(), pluginManager.isPluginEnabled(plugin));
            }
        }

        // Quiesce BEFORE mutating anything. LoopedPluginManager.unregister only sets a stopped flag;
        // the executor does not observe it until after its current sleep, and the thread is neither
        // interrupted nor joined. Starting a second generation while the first is still executing is
        // the single most confusing failure this design can produce, so refuse rather than risk it.
        if (outgoing != null && !stopAndQuiesce(outgoing)) {
            lastError = "a looped plugin thread did not stop within " + QUIESCE_MILLIS + " ms";
            log.error("[hotswap] {} - keeping generation {}", lastError, generation);
            candidate.loader.close();
            return false;
        }

        List<Plugin> loaded;
        try {
            loaded = onEdt(() -> {
                var started = pluginManager.loadPlugins(candidate.classes, null);
                pluginManager.loadDefaultPluginConfiguration(started);
                for (var plugin : started) {
                    var enabled = enabledStates.getOrDefault(plugin.getClass().getName(), Boolean.TRUE);
                    pluginManager.setPluginEnabled(plugin, enabled);
                    if (enabled) {
                        pluginManager.startPlugin(plugin);
                    }
                }
                return started;
            });
        } catch (Throwable e) {
            lastError = "load: " + e.getMessage();
            log.error("[hotswap] loading the new generation failed; the previous one is gone", e);
            candidate.loader.close();
            return false;
        }

        // Close the outgoing loader only after the new generation is up, so a class the outgoing
        // plugins had not yet touched during shutdown does not vanish underneath them.
        if (outgoing != null) {
            closeQuietly(outgoing);
        }

        current = new Generation(candidate.loader, loaded, Instant.now());
        generation++;
        lastError = null;
        log.info("[hotswap] generation {} loaded: {}", generation, current.pluginIds());

        Static.getWrappedClient().getCallbacks().post(new ExternalPluginsChanged());
        return true;
    }

    /** plugins/dev declared no plugin classes. Not an error - see the handler in doReload. */
    private static final class NoDevPlugins extends IOException {
        private NoDevPlugins() {
            super("no dev plugins declared");
        }
    }

    /**
     * Loads and validates the candidate classes before anything is torn down, so a broken jar costs
     * nothing. Relaxes easy-rl's {@code getSuperclass() == Plugin.class} to an assignability check:
     * Solace plugins commonly extend {@code LoopedPlugin} or {@code TaskPlugin}.
     */
    private Candidate preflight(Path source) throws Exception {
        if (!Files.isRegularFile(source)) {
            throw new IOException("jar not found: " + source);
        }

        List<String> names;
        try (var jarFile = new JarFile(source.toFile())) {
            var manifest = jarFile.getManifest();
            var declared = manifest == null
                    ? null
                    : manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
            // Absent means the jar was not built by :devplugins - a real misconfiguration. Present but
            // empty is the ordinary state when plugins/dev holds no sources, so it must not be an
            // error: the watcher would otherwise reject the jar on every rebuild forever.
            if (declared == null) {
                throw new IOException("manifest has no " + MANIFEST_ATTRIBUTE);
            }
            names = new ArrayList<>();
            for (var name : declared.split(",")) {
                if (!name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
        }

        if (names.isEmpty()) {
            throw new NoDevPlugins();
        }

        var loader = DevPluginClassLoader.open(source, getClass().getClassLoader());
        try {
            var classes = new ArrayList<Class<?>>();
            for (var name : names) {
                var type = loader.loadClass(name);
                if (!Plugin.class.isAssignableFrom(type)) {
                    throw new IOException(name + " does not extend net.solace.api.plugins.Plugin");
                }
                if (type.getAnnotation(PluginDescriptor.class) == null) {
                    throw new IOException(name + " has no @PluginDescriptor");
                }
                if (type.getClassLoader() != loader) {
                    throw new IOException(name + " was loaded by the parent; it must be in the jar");
                }
                classes.add(type);
            }
            return new Candidate(loader, classes);
        } catch (Exception e) {
            closeQuietly(loader);
            throw e;
        }
    }

    /**
     * Stops the outgoing generation and waits for its looped threads to actually die. Returns false
     * if any refuses, in which case the caller must abort rather than create a second generation.
     */
    private boolean stopAndQuiesce(Generation outgoing) throws Exception {
        var threadNames = new ArrayList<String>();
        for (var plugin : outgoing.plugins) {
            if (plugin instanceof LoopedPlugin) {
                threadNames.add(LoopedPluginManager.threadName((LoopedPlugin) plugin));
            }
        }

        onEdt(() -> {
            for (var plugin : outgoing.plugins) {
                try {
                    if (pluginManager.isPluginActive(plugin)) {
                        pluginManager.stopPlugin(plugin);
                    }
                    pluginManager.remove(plugin);
                } catch (Exception e) {
                    log.warn("[hotswap] failed to stop {}", plugin.getClass().getName(), e);
                }
            }
            return null;
        });

        var deadline = System.currentTimeMillis() + QUIESCE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!anyAlive(threadNames)) {
                return true;
            }
            Thread.sleep(50);
        }
        return !anyAlive(threadNames);
    }

    private static boolean anyAlive(List<String> threadNames) {
        if (threadNames.isEmpty()) {
            return false;
        }
        for (var thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && threadNames.contains(thread.getName())) {
                return true;
            }
        }
        return false;
    }

    private static <T> T onEdt(EdtTask<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.run();
        }
        var result = new Object[1];
        var failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = task.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        var typed = (T) result[0];
        return typed;
    }

    private long pollMillis() {
        try {
            return Long.parseLong(System.getProperty(POLL_PROPERTY, String.valueOf(DEFAULT_POLL_MILLIS)));
        } catch (NumberFormatException e) {
            return DEFAULT_POLL_MILLIS;
        }
    }

    private static void closeQuietly(Generation generation) {
        closeQuietly(generation.loader);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("[hotswap] failed to close a classloader", e);
        }
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        if (current != null) {
            closeQuietly(current);
            current = null;
        }
    }

    @FunctionalInterface
    private interface EdtTask<T> {
        T run() throws Exception;
    }

    private static final class Candidate {
        private final DevPluginClassLoader loader;
        private final List<Class<?>> classes;

        private Candidate(DevPluginClassLoader loader, List<Class<?>> classes) {
            this.loader = loader;
            this.classes = classes;
        }
    }

    private static final class Generation {
        private final DevPluginClassLoader loader;
        private final List<Plugin> plugins;
        private final Instant loadedAt;

        private Generation(DevPluginClassLoader loader, List<Plugin> plugins, Instant loadedAt) {
            this.loader = loader;
            this.plugins = plugins;
            this.loadedAt = loadedAt;
        }

        private List<String> pluginIds() {
            var ids = new ArrayList<String>();
            for (var plugin : plugins) {
                ids.add(plugin.getClass().getName());
            }
            return ids;
        }
    }
}

package net.solace.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Watches the layer jars and reloads when they settle.
 *
 * <p>Generalises {@code DevPluginJarWatcher} from one jar to nine, with a <b>composite</b> stamp. That
 * matters: {@code gradle -t layerJars} writes the jars seconds apart, and per-file stamps would fire a
 * reload for each one. Requiring the whole set to be unchanged across two consecutive polls collapses
 * a multi-module rebuild into a single reload.
 *
 * <p>Polling rather than {@code WatchService} for the same reason as the dev-plugin watcher: a rebuild
 * produces a burst of events for files that are still being written, and seen-twice is a simpler and
 * more reliable debounce than trying to interpret those events.
 */
final class LayerWatcher implements AutoCloseable {
    static final String POLL_PROPERTY = "solace.layer.pollMillis";
    private static final long DEFAULT_POLL_MILLIS = 750;
    private static final long MIN_POLL_MILLIS = 200;

    private final Function<List<Path>, Boolean> reload;
    private final long interval;

    private List<Stamp> accepted;
    private List<Stamp> pending;
    private List<Stamp> rejected;
    private volatile boolean running;
    private Thread thread;

    LayerWatcher(Function<List<Path>, Boolean> reload) {
        this.reload = reload;
        this.interval = Math.max(MIN_POLL_MILLIS, pollMillis());
    }

    synchronized void start() {
        if (running) {
            return;
        }
        accepted = read();
        running = true;
        thread = new Thread(this::loop, "solace-layer-watcher");
        thread.setDaemon(true);
        thread.start();
        System.out.println("[layer] watching " + SolaceLayerLauncher.layerJars().size()
                + " jars every " + interval + " ms");
    }

    synchronized void poll() {
        var current = read();
        if (current == null) {
            // A jar vanished, which `gradle clean` does briefly. Keep the running generation.
            pending = null;
            return;
        }
        if (current.equals(accepted)) {
            pending = null;
            rejected = null;
            return;
        }
        if (current.equals(rejected)) {
            // Already tried this exact build and it failed. Don't retry until something changes.
            pending = null;
            return;
        }
        if (!current.equals(pending)) {
            // Seen once. Wait for a second identical reading so a half-written jar is never loaded.
            pending = current;
            return;
        }

        pending = null;
        if (Boolean.TRUE.equals(reload.apply(SolaceLayerLauncher.layerJars()))) {
            accepted = current;
            rejected = null;
        } else {
            rejected = current;
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(interval);
                if (running) {
                    poll();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                System.err.println("[layer] watcher failed: " + e);
            }
        }
    }

    /** Null if any jar is missing - the set is only meaningful as a whole. */
    private List<Stamp> read() {
        var stamps = new ArrayList<Stamp>();
        for (var jar : jarsOrEmpty()) {
            if (!Files.isRegularFile(jar)) {
                return null;
            }
            try {
                stamps.add(new Stamp(Files.size(jar), Files.getLastModifiedTime(jar)));
            } catch (IOException e) {
                return null;
            }
        }
        return stamps.isEmpty() ? null : stamps;
    }

    private static List<Path> jarsOrEmpty() {
        try {
            return SolaceLayerLauncher.layerJars();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static long pollMillis() {
        try {
            return Long.parseLong(System.getProperty(POLL_PROPERTY, String.valueOf(DEFAULT_POLL_MILLIS)));
        } catch (NumberFormatException e) {
            return DEFAULT_POLL_MILLIS;
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private static final class Stamp {
        private final long size;
        private final FileTime modified;

        private Stamp(long size, FileTime modified) {
            this.size = size;
            this.modified = modified;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Stamp)) {
                return false;
            }
            var stamp = (Stamp) other;
            return size == stamp.size && modified.equals(stamp.modified);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, modified);
        }
    }
}

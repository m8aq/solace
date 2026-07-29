package net.solace.loader.hotswap;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.function.Function;

/**
 * Polls the dev plugin jar and triggers a reload when it settles.
 *
 * <p>Polling rather than {@code WatchService}: a Gradle rebuild produces a burst of filesystem events
 * for a file that is still being written, and the stamp-seen-twice rule below is a simpler and more
 * reliable debounce than trying to interpret those events.
 *
 * <p>Four states, each earning its place:
 * <ul>
 *   <li><b>accepted</b> - the stamp currently loaded. Seeing it again is a no-op.
 *   <li><b>pending</b> - seen once. A reload waits for a second identical reading, so a half-written
 *       jar is never loaded.
 *   <li><b>rejected</b> - failed to load. Not retried until the file changes again, so a broken build
 *       does not reload-fail in a loop.
 *   <li><b>null</b> - the jar vanished, which {@code gradle clean} does briefly. The running
 *       generation is kept.
 * </ul>
 */
@Slf4j
final class DevPluginJarWatcher implements AutoCloseable {
    private static final long MIN_INTERVAL_MILLIS = 200;

    private final Path jar;
    private final long interval;
    private final Function<Path, Boolean> reload;

    private Stamp accepted;
    private Stamp pending;
    private Stamp rejected;
    private volatile boolean running;
    private Thread thread;

    DevPluginJarWatcher(Path jar, long intervalMillis, Function<Path, Boolean> reload) {
        this.jar = jar;
        this.interval = Math.max(MIN_INTERVAL_MILLIS, intervalMillis);
        this.reload = reload;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        accepted = read();
        running = true;
        thread = new Thread(this::loop, "solace-devplugins-watcher");
        thread.setDaemon(true);
        thread.start();
        log.info("[hotswap] watching {} every {} ms", jar, interval);
    }

    synchronized void poll() {
        var current = read();
        if (current == null) {
            pending = null;
            return;
        }
        if (current.equals(accepted)) {
            pending = null;
            rejected = null;
            return;
        }
        if (current.equals(rejected)) {
            pending = null;
            return;
        }
        if (!current.equals(pending)) {
            pending = current;
            return;
        }

        pending = null;
        if (Boolean.TRUE.equals(reload.apply(jar))) {
            accepted = current;
            rejected = null;
        } else {
            rejected = current;
        }
    }

    /** Marks the current jar as loaded, for a reload triggered from outside the watcher. */
    synchronized void markAccepted() {
        accepted = read();
        pending = null;
        rejected = null;
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
                log.warn("[hotswap] jar watcher failed", e);
            }
        }
    }

    private Stamp read() {
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try {
            return new Stamp(Files.size(jar), Files.getLastModifiedTime(jar));
        } catch (IOException e) {
            log.debug("[hotswap] unable to inspect {}", jar, e);
            return null;
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

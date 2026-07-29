package net.solace.loader.controlapi;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;
import net.solace.loader.commons.Directories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads and reaps the per-PID endpoint files {@link ApiAccessToken} writes.
 *
 * <p>Exists to answer one question, asked only on the failure path: <em>who already has the port?</em>
 * A {@link java.net.BindException} on a fixed loopback port is not a mystery worth twenty minutes, but
 * it reliably becomes one, because the process that holds the port answers requests perfectly happily -
 * so the port looks healthy while serving code from somewhere else entirely.
 *
 * <p>Nothing reaped these files before, so the directory accumulated an entry per dev run and could not
 * be trusted to say who was live. {@link #reapStale()} makes it authoritative by dropping any file
 * whose PID is gone, which is what lets {@link #describeOwner} distinguish "another client is running"
 * from "something that is not Solace has the port".
 */
@Slf4j
public final class ControlApiEndpoints {
    private static final Gson GSON = new Gson();

    private ControlApiEndpoints() {
    }

    /** One endpoint file's contents. Fields are populated reflectively by Gson. */
    private static final class Endpoint {
        private long pid;
        private int port;
        private String startedAt;
    }

    public static Path directory() {
        return Directories.SOLACE_DIR.resolve("controlapi");
    }

    /**
     * Deletes endpoint files whose process is gone.
     *
     * <p>Best-effort by design: a file we cannot read or delete is skipped rather than escalated. This
     * runs on the startup path of a dev tool, and failing to tidy a stale file is never worth failing
     * to start the API.
     */
    public static void reapStale() {
        for (var entry : readAll()) {
            if (isAlive(entry.pid())) {
                continue;
            }
            try {
                Files.deleteIfExists(entry.path());
                log.debug("[control-api] reaped stale endpoint file {} (pid {} is gone)",
                        entry.path(), entry.pid());
            } catch (IOException e) {
                log.debug("[control-api] could not reap {}", entry.path(), e);
            }
        }
    }

    /**
     * Describes whatever live Solace process is already listening on {@code port}, for use in an error
     * message. Empty when no endpoint file claims the port - which usually means the owner is not
     * Solace at all, so the caller should say so rather than assert anything.
     */
    public static Optional<String> describeOwner(int port) {
        for (var entry : readAll()) {
            if (entry.port() != port || !isAlive(entry.pid())) {
                continue;
            }
            // Our own pid means the outgoing layer generation never closed its server - a completely
            // different problem from a second client, and the one worth naming precisely, because the
            // port then serves stale code from inside this very process.
            if (entry.pid() == ProcessHandle.current().pid()) {
                return Optional.of("THIS process (pid " + entry.pid() + ") - a previous layer "
                        + "generation's ApiServer was never closed, so the port is still serving its "
                        + "code; ControlApiPlugin.shutDown() did not run during teardown");
            }
            var startedAt = entry.startedAt() == null ? "an unknown time" : entry.startedAt();
            return Optional.of("another Solace client (pid " + entry.pid()
                    + ", started " + startedAt + ")");
        }
        return Optional.empty();
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static List<Entry> readAll() {
        var dir = directory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        var entries = new ArrayList<Entry>();
        try (Stream<Path> files = Files.list(dir)) {
            for (var path : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                readOne(path).ifPresent(entries::add);
            }
        } catch (IOException e) {
            log.debug("[control-api] could not list {}", dir, e);
        }
        return entries;
    }

    private static Optional<Entry> readOne(Path path) {
        try {
            var json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            var endpoint = GSON.fromJson(json, Endpoint.class);
            if (endpoint == null || endpoint.pid == 0) {
                return Optional.empty();
            }
            return Optional.of(new Entry(path, endpoint.pid, endpoint.port, endpoint.startedAt));
        } catch (IOException | JsonParseException e) {
            // A truncated or hand-edited file tells us nothing; it is not an error worth reporting.
            log.debug("[control-api] could not read {}", path, e);
            return Optional.empty();
        }
    }

    private static final class Entry {
        private final Path path;
        private final long pid;
        private final int port;
        private final String startedAt;

        Entry(Path path, long pid, int port, String startedAt) {
            this.path = path;
            this.pid = pid;
            this.port = port;
            this.startedAt = startedAt;
        }

        Path path() {
            return path;
        }

        long pid() {
            return pid;
        }

        int port() {
            return port;
        }

        String startedAt() {
            return startedAt;
        }
    }
}

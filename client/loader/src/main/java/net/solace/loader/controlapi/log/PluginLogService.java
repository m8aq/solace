package net.solace.loader.controlapi.log;

import net.solace.api.plugins.Plugin;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.Redaction;
import net.solace.loader.plugins.LoopedPluginManager;
import net.solace.loader.plugins.PluginManagerImpl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers log events per plugin and fans them out to SSE subscribers.
 *
 * <p>Two additions over easy-rl's version, both aimed at the case where you most need logs:
 * <ul>
 *   <li>{@link #ROOT_ID} - a pseudo-plugin receiving <em>every</em> event, including loader and
 *       RuneLite lines that belong to no plugin. easy-rl drops unattributed events entirely, which
 *       means a failure during startup or {@code loadCorePlugins()} is invisible to its API.
 *   <li>{@code requireRunning=false} on subscribe, so you can read the tail of a plugin that just
 *       died rather than being refused with {@code PLUGIN_NOT_RUNNING}.
 * </ul>
 */
public final class PluginLogService implements AutoCloseable {
    /** Pseudo-plugin id carrying the unfiltered stream. */
    public static final String ROOT_ID = "__root__";

    private static final int SUBSCRIBER_LIMIT = 256;

    /**
     * Each SSE stream pins an HTTP pool thread for its whole life, so this bounds threads, not just
     * memory. Kept well under the server's pool ceiling so log streams can never starve commands.
     */
    private static final int MAX_CONCURRENT_STREAMS = 8;

    private final PluginManagerImpl pluginManager;
    private final int historyLimit;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Deque<PluginLogEvent>> history = new HashMap<>();
    private final Map<String, Set<PluginLogStream>> subscribers = new HashMap<>();
    private int streamCount;
    private boolean closed;

    public PluginLogService(PluginManagerImpl pluginManager, int historyLimit) {
        this.pluginManager = pluginManager;
        this.historyLimit = Math.max(1, historyLimit);
    }

    public void publish(long timestamp, String level, String logger, String thread, String message,
                        String throwable) {
        var pluginId = attribute(logger, thread);
        var safeMessage = Redaction.redactText(message);
        var safeThrowable = Redaction.redactText(throwable);

        synchronized (this) {
            if (closed) {
                return;
            }
            // Sequence numbers are allocated under the lock so Last-Event-ID resume is monotonic
            // across both the per-plugin and root streams.
            if (pluginId != null) {
                dispatch(new PluginLogEvent(sequence.incrementAndGet(), timestamp, pluginId, level,
                        logger, thread, safeMessage, safeThrowable, "log"), pluginId);
            }
            dispatch(new PluginLogEvent(sequence.incrementAndGet(), timestamp,
                    pluginId == null ? ROOT_ID : pluginId, level, logger, thread, safeMessage,
                    safeThrowable, "log"), ROOT_ID);
        }
    }

    private void dispatch(PluginLogEvent event, String bucket) {
        var buffer = history.computeIfAbsent(bucket, ignored -> new ArrayDeque<>());
        buffer.addLast(event);
        while (buffer.size() > historyLimit) {
            buffer.removeFirst();
        }

        var streams = subscribers.get(bucket);
        if (streams != null) {
            for (var stream : new ArrayList<>(streams)) {
                stream.offer(event);
            }
        }
    }

    public synchronized List<PluginLogEvent> tail(String pluginId, int limit)
            throws ApiCommandException {
        requireKnown(pluginId, false);
        var buffer = history.get(pluginId);
        if (buffer == null) {
            return List.of();
        }
        var all = new ArrayList<>(buffer);
        var from = limit <= 0 ? 0 : Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    public synchronized PluginLogStream subscribe(String pluginId, int tail, long lastEventId,
                                                  boolean requireRunning) throws ApiCommandException {
        if (closed) {
            throw new ApiCommandException("INTERNAL_ERROR", "Log service is shutting down");
        }
        requireKnown(pluginId, requireRunning);

        if (streamCount >= MAX_CONCURRENT_STREAMS) {
            throw new ApiCommandException("TOO_MANY_STREAMS",
                    "At most " + MAX_CONCURRENT_STREAMS + " concurrent log streams are allowed");
        }

        var streams = subscribers.computeIfAbsent(pluginId, ignored -> new HashSet<>());
        var holder = new PluginLogStream[1];
        var stream = new PluginLogStream(SUBSCRIBER_LIMIT, () -> remove(pluginId, holder[0]));
        holder[0] = stream;
        streams.add(stream);
        streamCount++;

        replay(pluginId, tail, lastEventId, stream);
        return stream;
    }

    private void replay(String pluginId, int tail, long lastEventId, PluginLogStream stream) {
        var buffer = history.get(pluginId);
        if (buffer == null || (tail <= 0 && lastEventId < 0)) {
            return;
        }
        var replayLimit = tail > 0 ? Math.min(tail, historyLimit) : historyLimit;
        var skip = Math.max(0, buffer.size() - replayLimit);
        var index = 0;
        for (var event : buffer) {
            if (index++ >= skip && event.getSequence() > lastEventId) {
                stream.offer(event);
            }
        }
    }

    private void requireKnown(String pluginId, boolean requireRunning) throws ApiCommandException {
        if (ROOT_ID.equals(pluginId)) {
            return;
        }
        Plugin target = null;
        for (var plugin : pluginManager.getPlugins()) {
            if (plugin.getClass().getName().equals(pluginId)) {
                target = plugin;
                break;
            }
        }
        if (target == null) {
            throw new ApiCommandException("PLUGIN_NOT_FOUND", "No plugin matched the supplied ID");
        }
        if (requireRunning && !pluginManager.isPluginActive(target)) {
            throw new ApiCommandException("PLUGIN_NOT_RUNNING",
                    "Plugin is not running; pass requireRunning=false to read its buffered tail");
        }
    }

    /** Emits the terminal marker so a subscriber's stream ends rather than hanging on heartbeats. */
    public synchronized void pluginStopped(String pluginId, String reason) {
        var streams = subscribers.get(pluginId);
        if (streams == null) {
            return;
        }
        var stopped = new PluginLogEvent(sequence.incrementAndGet(), System.currentTimeMillis(),
                pluginId, "INFO", "", "", reason, null, "plugin-stopped");
        for (var stream : new ArrayList<>(streams)) {
            stream.offer(stopped);
        }
    }

    private synchronized void remove(String pluginId, PluginLogStream stream) {
        var streams = subscribers.get(pluginId);
        if (streams != null && streams.remove(stream)) {
            streamCount = Math.max(0, streamCount - 1);
            if (streams.isEmpty()) {
                subscribers.remove(pluginId);
            }
        }
    }

    /**
     * Maps a log event to the plugin that owns it.
     *
     * <p>Thread name first: a looped plugin's own exceptions are logged by
     * {@code LoopedPluginExecutor}, whose logger lives in the parent package and therefore matches no
     * plugin by name - so package-prefix attribution alone drops exactly the lines you most want on a
     * per-plugin stream. The executor runs on a thread named by
     * {@link LoopedPluginManager#threadName}, which identifies the plugin exactly.
     *
     * <p>Falling back to longest matching package prefix. Returns null when nothing matches or when
     * two plugins tie - an ambiguous attribution is worse than none.
     */
    private String attribute(String logger, String thread) {
        if (thread != null && thread.startsWith(LoopedPluginManager.THREAD_PREFIX)) {
            return thread.substring(LoopedPluginManager.THREAD_PREFIX.length());
        }
        if (logger == null) {
            return null;
        }

        var candidates = new ArrayList<Plugin>();
        for (var plugin : pluginManager.getPlugins()) {
            var className = plugin.getClass().getName();
            var split = className.lastIndexOf('.');
            var packageName = split < 0 ? className : className.substring(0, split);
            if (logger.equals(className) || logger.startsWith(packageName + ".")) {
                candidates.add(plugin);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingInt(
                (Plugin plugin) -> plugin.getClass().getPackage().getName().length()).reversed());

        var longest = candidates.get(0).getClass().getPackage().getName().length();
        if (candidates.size() > 1
                && candidates.get(1).getClass().getPackage().getName().length() == longest) {
            return null;
        }
        return candidates.get(0).getClass().getName();
    }

    @Override
    public synchronized void close() {
        closed = true;
        var allStreams = new ArrayList<PluginLogStream>();
        for (var streams : subscribers.values()) {
            allStreams.addAll(streams);
        }
        subscribers.clear();
        history.clear();
        streamCount = 0;
        for (var stream : allStreams) {
            stream.close();
        }
    }
}

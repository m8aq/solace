package net.solace.loader.controlapi.log;

import lombok.Getter;

import java.time.Instant;

/** One log line, as it goes out over SSE. Serialized by gson, so every field is on the wire. */
@Getter
public final class PluginLogEvent {
    private final long sequence;
    private final String timestamp;
    private final String pluginId;
    private final String level;
    private final String logger;
    private final String thread;
    private final String message;
    private final String throwable;

    /** {@code log} for an ordinary line, {@code plugin-stopped} for the terminal marker. */
    private final String event;

    public PluginLogEvent(long sequence, long timestamp, String pluginId, String level, String logger,
                          String thread, String message, String throwable, String event) {
        this.sequence = sequence;
        this.timestamp = Instant.ofEpochMilli(timestamp).toString();
        this.pluginId = pluginId;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
        this.throwable = throwable;
        this.event = event;
    }
}

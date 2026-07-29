package net.solace.loader.controlapi.log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One subscriber's bounded view of the log firehose.
 *
 * <p>Drops the oldest event rather than blocking when full: a slow HTTP client must never stall the
 * logging thread, which is every thread in the process.
 */
public final class PluginLogStream implements AutoCloseable {
    private final ArrayBlockingQueue<PluginLogEvent> events;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    PluginLogStream(int capacity, Runnable onClose) {
        this.events = new ArrayBlockingQueue<>(capacity);
        this.onClose = onClose;
    }

    void offer(PluginLogEvent event) {
        if (!closed.get() && !events.offer(event)) {
            events.poll();
            events.offer(event);
        }
    }

    public PluginLogEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        return events.poll(timeout, unit);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
            events.clear();
        }
    }
}

package net.solace.loader.controlapi;

/**
 * Carries the moment a command's client-thread read actually happened, from the client-thread bridge
 * back to the HTTP thread building the response.
 *
 * <p>Exists so {@code capturedAt} reports when state was read rather than when the response was
 * serialized. The two differ by however long the client thread queued the work, which is exactly the
 * interval a caller needs to see when checking whether a batch of reads straddled a game tick.
 *
 * <p>The value is thread-local to the request-handling thread and must be cleared at the start of
 * each request - handler threads are pooled and reused, so a pure command that never touched the
 * client thread would otherwise inherit the previous request's timestamp.
 */
public final class CaptureClock {
    private static final ThreadLocal<Long> CAPTURED_AT = new ThreadLocal<>();

    private CaptureClock() {
    }

    /** Records a client-thread read. The most recent read in a request wins. */
    public static void mark(long epochMillis) {
        CAPTURED_AT.set(epochMillis);
    }

    /** The request's client-thread read time, or {@code null} when it made none. */
    public static Long taken() {
        return CAPTURED_AT.get();
    }

    public static void clear() {
        CAPTURED_AT.remove();
    }
}

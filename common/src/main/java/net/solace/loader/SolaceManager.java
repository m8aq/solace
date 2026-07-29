package net.solace.loader;

import java.util.List;

public interface SolaceManager {
    /** Equivalent to {@code start(true)}. */
    void start() throws Exception;

    /**
     * @param firstInit false on a hot reload, which suppresses the steps that mutate RuneLite state
     *                  surviving the reload - sideloaded plugins and the incompatible-plugin check.
     */
    void start(boolean firstInit) throws Exception;

    /**
     * Tears Solace down so its classloader generation can be collected.
     *
     * @return failures encountered, empty when teardown was clean. Never throws - a teardown that
     *         aborts partway leaves the client in an indeterminate state, so failures are collected
     *         and reported rather than propagated.
     */
    List<String> unload();
}

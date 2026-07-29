package net.solace.loader.hotswap;

/**
 * Hands the dev-plugin hot-swap service to code that cannot reach the object that built it.
 *
 * <p>The service is created by the dev entry point before the plugin manager exists; the control API
 * and the teardown path both need it afterwards. Resolved per call, never captured - the control
 * API's plugin starts during initialisation, before this is populated.
 */
public final class DevPluginHotSwapHolder {
    private static volatile DevPluginHotSwapService instance;

    private DevPluginHotSwapHolder() {
    }

    public static void set(DevPluginHotSwapService service) {
        instance = service;
    }

    public static DevPluginHotSwapService get() {
        return instance;
    }

    /**
     * Closes the service if one is running. Must be the first step of teardown: the watcher thread and
     * the dev-plugin classloader are parented to the current generation, so a dev-plugin generation
     * must never outlive the layer it was built against.
     */
    public static void closeIfPresent() {
        var service = instance;
        instance = null;
        if (service != null) {
            service.close();
        }
    }
}

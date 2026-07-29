package net.solace.loader.plugins;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.eventbus.EventBus;
import net.solace.api.commons.ITime;
import net.solace.api.domain.game.IClient;
import net.solace.api.game.IGame;
import net.solace.api.plugins.IPlugins;
import net.solace.api.plugins.LoopedPlugin;
import net.solace.api.plugins.Task;
import net.solace.api.plugins.TaskPlugin;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class LoopedPluginManager {
    public static final String THREAD_PREFIX = "solace-plugin/";

    private final Map<LoopedPlugin, LoopedPluginExecutor<?>> loopedPlugins = new HashMap<>();

    /**
     * The thread running each plugin, kept so {@link #unregister} can interrupt it. Previously the
     * reference was dropped on the floor, which meant a stopped plugin kept running until its current
     * sleep elapsed - up to seconds.
     */
    private final Map<LoopedPlugin, Thread> threads = new HashMap<>();

    private final EventBus eventBus;
    private final IGame game;
    private final IPlugins plugins;
    private final ITime time;
    private final IClient client;
    private final ChatMessageManager chatMessageManager;


    public void register(LoopedPlugin plugin) {
        log.debug("Registering {} as a LoopedPlugin", plugin.getName());

        var executor = new DefaultLoopedPluginExecutor(plugin, game, plugins, time, client, chatMessageManager);
        loopedPlugins.put(plugin, executor);

        // Named so a stack dump and the log stream can both tell which plugin a thread belongs to.
        // LoopedPluginExecutor logs a plugin's exceptions under its own logger, which sits in the
        // parent package, so the thread name is the only thing tying those lines back to the plugin.
        var newThread = new Thread(executor, threadName(plugin));
        threads.put(plugin, newThread);

        if (plugin instanceof TaskPlugin) {
            for (Task task : ((TaskPlugin) plugin).getTasks()) {
                if (task.subscribe()) {
                    eventBus.register(task);
                }

                if (task.inject()) {
                    plugin.getInjector().injectMembers(task);
                }
            }
        }

        newThread.start();
    }

    /** {@code solace-plugin/<fully qualified class name>} - parsed back out by the control API. */
    public static String threadName(LoopedPlugin plugin) {
        return THREAD_PREFIX + plugin.getClass().getName();
    }

    public void unregister(LoopedPlugin plugin) {
        var executor = loopedPlugins.remove(plugin);
        if (executor == null) {
            log.warn("Tried to unregister LoopedPlugin {}, but it was not registered", plugin.getName());
            return;
        }

        log.debug("Unregistering {} as a LoopedPlugin", plugin.getName());

        executor.stop();

        // stop() only sets a flag, and LoopedPluginExecutor re-checks it after its sleep in the
        // finally block - so without this the plugin keeps running for up to a full sleep interval
        // after being stopped. TimeImpl.sleep catches InterruptedException and returns, which lets the
        // loop reach its !isStopped() check immediately.
        var thread = threads.remove(plugin);
        if (thread != null) {
            thread.interrupt();
        }

        if (plugin instanceof TaskPlugin) {
            for (Task task : ((TaskPlugin) plugin).getTasks()) {
                if (task.subscribe()) {
                    eventBus.unregister(task);
                }
            }
        }
    }
}

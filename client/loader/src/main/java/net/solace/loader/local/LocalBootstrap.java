package net.solace.loader.local;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.solace.api.events.ExternalPluginsChanged;
import net.solace.api.plugins.Plugin;
import net.solace.api.plugins.PluginManager;
import net.solace.loader.events.SdnLoaded;
import net.solace.sdn.SdnPluginManager;

import javax.swing.SwingUtilities;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class LocalBootstrap {
    private final SdnPluginManager sdnPluginManager;
    private final PluginManager pluginManager;
    private final EventBus eventBus;
    private final String script;

    public void loadPlugins() {
        sdnPluginManager.startExternalPluginManager();
        sdnPluginManager.startPlugins();

        pluginManager.loadDefaultPluginConfiguration(null);
        pluginManager.startPlugins();

        if (script != null) {
            pluginManager.getPlugins().stream()
                    .filter(p -> Objects.equals(p.getName(), script))
                    .findFirst()
                    .ifPresentOrElse(this::startPlugin, () -> log.warn("Plugin '{}' not found", script));
        }

        eventBus.post(new SdnLoaded());
        eventBus.post(new ExternalPluginsChanged());
    }

    /**
     * Stops and unloads the pf4j external plugins.
     *
     * <p>Their classloaders are parented to the current Solace generation, and
     * {@code SdnPluginManagerImpl.pluginClassLoaders} is a static list that only grows - so without
     * this every reload leaks one classloader per external plugin. The Solace-side {@code Plugin}
     * instances are stopped separately by {@code pluginManager.stopPlugins()}; this releases pf4j's
     * own wrappers and loaders.
     */
    public void unloadPlugins() {
        var pf4j = sdnPluginManager.getPluginManager();
        if (pf4j == null) {
            return;
        }

        try {
            pf4j.stopPlugins();
        } catch (RuntimeException e) {
            log.warn("Failed to stop external plugins", e);
        }

        // Unload from a copy - unloadPlugin mutates the list this iterates.
        for (var wrapper : new java.util.ArrayList<>(pf4j.getPlugins())) {
            try {
                pf4j.unloadPlugin(wrapper.getPluginId());
            } catch (RuntimeException e) {
                log.warn("Failed to unload external plugin {}", wrapper.getPluginId(), e);
            }
        }
    }

    private void startPlugin(Plugin plugin) {
        SwingUtilities.invokeLater(() -> {
            try {
                pluginManager.setPluginEnabled(plugin, true);
                pluginManager.startPlugin(plugin);
            } catch (Exception e) {
                log.error("Error starting plugin {}", plugin.getName(), e);
            }
        });
    }
}

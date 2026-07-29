package net.solace.loader.controlapi.service;

import lombok.RequiredArgsConstructor;
import net.solace.api.plugins.LoopedPlugin;
import net.solace.api.plugins.Plugin;
import net.solace.api.plugins.PluginDescriptor;
import net.solace.api.plugins.exception.PluginInstantiationException;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.thread.EdtBridge;
import net.solace.loader.plugins.PluginManagerImpl;
import net.solace.loader.plugins.controlapi.ControlApiPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs {@code plugins.*}. Every method goes through the EDT bridge because
 * {@code PluginManagerImpl.startPlugin}/{@code stopPlugin} assert they are on the event dispatch
 * thread.
 */
@RequiredArgsConstructor
public final class PluginCommandService {
    private static final String SELF_ID = ControlApiPlugin.class.getName();

    private final PluginManagerImpl pluginManager;
    private final EdtBridge edt;

    public List<Map<String, Object>> list() throws Exception {
        return edt.call(() -> {
            var result = new ArrayList<Map<String, Object>>();
            for (var plugin : pluginManager.getPlugins()) {
                result.add(status(plugin));
            }
            result.sort(Comparator.comparing(entry -> (String) entry.get("pluginId")));
            return result;
        });
    }

    public Map<String, Object> get(String pluginId) throws Exception {
        return edt.call(() -> status(requirePluginUnchecked(pluginId)));
    }

    public Map<String, Object> setEnabled(String pluginId, boolean enabled) throws Exception {
        requireNotSelf(pluginId);
        return edt.call(() -> {
            var plugin = requirePluginUnchecked(pluginId);
            var previousEnabled = pluginManager.isPluginEnabled(plugin);
            var previousActive = pluginManager.isPluginActive(plugin);
            try {
                apply(plugin, enabled);
                return status(plugin);
            } catch (PluginInstantiationException error) {
                rollback(plugin, previousEnabled, previousActive, error);
                throw new IllegalStateException("Plugin lifecycle operation failed", error);
            }
        });
    }

    /**
     * Stop then start, so a code or config change takes effect without a client restart. A plugin
     * that was not running is left alone rather than silently started.
     */
    public Map<String, Object> restart(String pluginId) throws Exception {
        requireNotSelf(pluginId);
        return edt.call(() -> {
            var plugin = requirePluginUnchecked(pluginId);
            if (!pluginManager.isPluginActive(plugin)) {
                throw new IllegalArgumentException("Plugin is not running: " + pluginId);
            }
            try {
                pluginManager.stopPlugin(plugin);
                pluginManager.setPluginEnabled(plugin, true);
                pluginManager.startPlugin(plugin);
                return status(plugin);
            } catch (PluginInstantiationException error) {
                throw new IllegalStateException("Plugin restart failed", error);
            }
        });
    }

    /**
     * {@code setPluginEnabled} before {@code startPlugin} is required, not stylistic:
     * {@code PluginManagerImpl.startPlugin} returns false immediately when the plugin is not enabled.
     */
    private void apply(Plugin plugin, boolean enabled) throws PluginInstantiationException {
        pluginManager.setPluginEnabled(plugin, enabled);
        if (enabled && !pluginManager.isPluginActive(plugin)) {
            pluginManager.startPlugin(plugin);
        } else if (!enabled && pluginManager.isPluginActive(plugin)) {
            pluginManager.stopPlugin(plugin);
        }
    }

    private void rollback(Plugin plugin, boolean previousEnabled, boolean previousActive,
                          PluginInstantiationException error) {
        try {
            pluginManager.setPluginEnabled(plugin, previousEnabled);
            if (previousActive && !pluginManager.isPluginActive(plugin)) {
                pluginManager.startPlugin(plugin);
            } else if (!previousActive && pluginManager.isPluginActive(plugin)) {
                pluginManager.stopPlugin(plugin);
            }
        } catch (PluginInstantiationException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

    /** Disabling the control API through its own server would sever the channel mid-request. */
    private void requireNotSelf(String pluginId) throws ApiCommandException {
        if (SELF_ID.equals(pluginId)) {
            throw new ApiCommandException("SELF_MANAGEMENT_NOT_ALLOWED",
                    "The control API cannot manage itself through its own server");
        }
    }

    public Plugin requirePlugin(String pluginId) throws ApiCommandException {
        for (var plugin : pluginManager.getPlugins()) {
            if (plugin.getClass().getName().equals(pluginId)) {
                return plugin;
            }
        }
        throw new ApiCommandException("PLUGIN_NOT_FOUND", "No plugin matched the supplied ID");
    }

    /** For use inside the EDT bridge, whose {@link java.util.function.Supplier} cannot throw checked. */
    Plugin requirePluginUnchecked(String pluginId) {
        try {
            return requirePlugin(pluginId);
        } catch (ApiCommandException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

    private Map<String, Object> status(Plugin plugin) {
        var descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
        var status = new LinkedHashMap<String, Object>();
        status.put("pluginId", plugin.getClass().getName());
        status.put("name", descriptor == null ? plugin.getClass().getSimpleName() : descriptor.name());
        status.put("description", descriptor == null ? "" : descriptor.description());
        status.put("configName", descriptor == null ? "" : descriptor.configName());
        status.put("tags", descriptor == null ? new String[0] : descriptor.tags());
        status.put("conflicts", descriptor == null ? new String[0] : descriptor.conflicts());
        status.put("hidden", descriptor != null && descriptor.hidden());
        status.put("enabledByDefault", descriptor != null && descriptor.enabledByDefault());
        status.put("enabled", pluginManager.isPluginEnabled(plugin));
        status.put("active", pluginManager.isPluginActive(plugin));
        status.put("looped", plugin instanceof LoopedPlugin);
        status.put("sdn", plugin.isSdn());

        var loader = plugin.getClass().getClassLoader();
        status.put("source", loader == PluginManagerImpl.class.getClassLoader() ? "core" : "external");
        status.put("classLoader", loader == null ? null : loader.getClass().getName());
        return status;
    }
}

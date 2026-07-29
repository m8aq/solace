package net.solace.loader.controlapi.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import lombok.RequiredArgsConstructor;
import net.solace.api.plugins.PluginManager;
import net.solace.api.plugins.config.ConfigDescriptor;
import net.solace.api.plugins.config.ConfigItem;
import net.solace.api.plugins.config.ConfigItemDescriptor;
import net.solace.api.plugins.config.ConfigManager;
import net.solace.loader.controlapi.thread.EdtBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backs {@code config.*}.
 *
 * <p>Runs on the EDT because a write posts {@code ConfigChanged}, which the Swing config panel
 * consumes to repaint - doing it off-thread races the UI.
 */
@RequiredArgsConstructor
public final class PluginConfigCommandService {
    /**
     * Substring match against {@code group.key}. Extended past easy-rl's list for Solace's surface:
     * the bank-pin plugin has a {@code pin},
     * and Jagex-launcher accounts carry {@code sessionId} / {@code characterId}.
     *
     * <p>{@code username} and {@code email} are here because Solace's autologin config does not mark
     * its username {@code secret}, and half a credential pair is still a credential - it is usually
     * an email address tied to a real account.
     */
    private static final String[] SENSITIVE_TERMS = {
            "password", "passwd", "secret", "token", "session", "credential", "privatekey",
            "authorization", "auth", "otp", "pin", "characterid", "sessionid", "username", "email",
    };

    private final PluginManager pluginManager;
    private final ConfigManager configManager;
    private final PluginCommandService plugins;
    private final Gson gson;
    private final EdtBridge edt;

    public List<Map<String, Object>> list(String pluginId) throws Exception {
        return edt.call(() -> {
            var context = descriptor(pluginId);
            var result = new ArrayList<Map<String, Object>>();
            for (var item : context.descriptor.getItems()) {
                if (!sensitive(item.getItem(), context.group)) {
                    result.add(item(context, item));
                }
            }
            return result;
        });
    }

    public Map<String, Object> get(String pluginId, String key) throws Exception {
        return edt.call(() -> {
            var context = descriptor(pluginId);
            return item(context, requireItem(context, key));
        });
    }

    public Map<String, Object> set(String pluginId, String key, JsonElement value) throws Exception {
        return edt.call(() -> {
            var context = descriptor(pluginId);
            var item = requireItem(context, key);

            Object converted;
            try {
                converted = gson.fromJson(value, item.getType());
                validateRange(item, converted);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "Value does not match the declared configuration type", error);
            }
            if (converted == null) {
                throw new IllegalArgumentException("Configuration value must not be null");
            }

            configManager.setConfiguration(context.group, key, converted);
            return item(context, item);
        });
    }

    public Map<String, Object> unset(String pluginId, String key) throws Exception {
        return edt.call(() -> {
            var context = descriptor(pluginId);
            var item = requireItem(context, key);
            configManager.unsetConfiguration(context.group, key);
            return item(context, item);
        });
    }

    private DescriptorContext descriptor(String pluginId) {
        var plugin = plugins.requirePluginUnchecked(pluginId);

        var config = pluginManager.getPluginConfigProxy(plugin);
        if (config == null) {
            throw new IllegalArgumentException("Plugin has no declared configuration");
        }

        var descriptor = configManager.getConfigDescriptor(config);
        return new DescriptorContext(descriptor, descriptor.getGroup().value());
    }

    /**
     * Resolves a key, refusing sensitive ones. Deliberately reports "unknown or protected" rather
     * than distinguishing the two - otherwise the error message confirms a secret key exists.
     */
    private ConfigItemDescriptor requireItem(DescriptorContext context, String key) {
        for (var item : context.descriptor.getItems()) {
            if (item.getItem().keyName().equals(key) && !sensitive(item.getItem(), context.group)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Configuration key is unknown or protected");
    }

    private Map<String, Object> item(DescriptorContext context, ConfigItemDescriptor descriptor) {
        var item = descriptor.getItem();
        var result = new LinkedHashMap<String, Object>();
        result.put("group", context.group);
        result.put("key", item.keyName());
        result.put("name", item.name());
        result.put("description", item.description());
        result.put("type", descriptor.getType().getTypeName());
        result.put("section", item.section());
        result.put("position", item.position());
        result.put("hidden", item.hidden());
        result.put("secret", item.secret());
        result.put("value", configManager.getConfiguration(context.group, item.keyName()));

        var range = descriptor.getRange();
        if (range != null) {
            result.put("minimum", range.min());
            result.put("maximum", range.max());
        }

        if (descriptor.getType() instanceof Class && ((Class<?>) descriptor.getType()).isEnum()) {
            result.put("choices", ((Class<?>) descriptor.getType()).getEnumConstants());
        }

        return result;
    }

    private static void validateRange(ConfigItemDescriptor descriptor, Object value) {
        if (descriptor.getRange() != null && value instanceof Number) {
            var number = ((Number) value).longValue();
            if (number < descriptor.getRange().min() || number > descriptor.getRange().max()) {
                throw new IllegalArgumentException("Value is outside the configured range");
            }
        }
    }

    /**
     * Deliberately does <em>not</em> treat {@code hidden} as sensitive, diverging from easy-rl. In
     * Solace {@code hidden} is a UI-conditional flag paired with {@code unhide}/{@code unhideValue} -
     * {@code SolaceAutoLoginConfig.world} is hidden until {@code useWorld} is on - so filtering on it
     * would make ordinary, non-secret config invisible to the API for no security benefit.
     */
    private static boolean sensitive(ConfigItem item, String group) {
        if (item.secret()) {
            return true;
        }
        var candidate = (group + "." + item.keyName()).toLowerCase(Locale.ROOT).replace("_", "");
        for (var term : SENSITIVE_TERMS) {
            if (candidate.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static final class DescriptorContext {
        private final ConfigDescriptor descriptor;
        private final String group;

        private DescriptorContext(ConfigDescriptor descriptor, String group) {
            this.descriptor = descriptor;
            this.group = group;
        }
    }
}

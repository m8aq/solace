package net.solace.loader.controlapi.service;

import lombok.RequiredArgsConstructor;
import net.runelite.api.GameState;
import net.solace.api.Static;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.Redaction;
import net.solace.loader.controlapi.thread.ClientThreadBridge;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backs {@code login.*}.
 *
 * <p>Does not implement its own login state machine - Solace already has the SDK's
 * {@code LoginEvent} and {@code EventManager}'s credential filler, and another would just be one more
 * writer racing them. This drives {@code SolaceAutoLoginPlugin} by toggling it, and reports what it
 * observes.
 *
 * <p>The plugin lives in {@code :bundled}, which {@code :loader} embeds but does not compile against,
 * so its diagnostic is read reflectively. A missing plugin degrades to a null diagnostic rather than
 * failing the command.
 */
@RequiredArgsConstructor
public final class LoginCommandService {
    /** Enough ticks for the tab to open and the button to be clicked, without hanging the request. */
    private static final int LOGOUT_ATTEMPTS = 8;
    private static final long LOGOUT_POLL_MILLIS = 600;

    private static final String PLUGIN_ID =
            "net.solace.loader.plugins.autologin.SolaceAutoLoginPlugin";

    private final PluginCommandService plugins;
    private final net.solace.loader.plugins.PluginManagerImpl pluginManager;
    private final ClientThreadBridge clientThread;

    public Map<String, Object> status() throws Exception {
        return clientThread.call(() -> {
            var result = new LinkedHashMap<String, Object>();
            var client = Static.getClient();
            var state = client.getGameState();

            result.put("gameState", state == null ? null : state.name());
            result.put("loggedIn", state == GameState.LOGGED_IN);
            result.put("world", client.getWorld());

            // Both of these go through reflection hooks from mappings/version-package.json, which rot
            // when the game revision moves. getLoginMessage currently fails with "Field not found:
            // loginResponse1". A stale hook must degrade one field, not fail the whole command -
            // status is the thing you reach for when everything else is broken.
            var loginIndex = safe(() -> client.getWrapped().getLoginIndex(), -1);
            result.put("loginIndex", loginIndex);
            result.put("loginIndexName", loginIndex < 0 ? "UNAVAILABLE" : loginIndexName(loginIndex));
            result.put("loginMessage", safe(client::getLoginMessage, null));

            var account = Static.getGame().getGameAccount();
            result.put("accountConfigured", account != null);
            result.put("accountSource", account != null ? "commandLine" : "config");
            result.put("jagexLauncher", account != null && account.isJagexLauncher());

            // Never echo the value - a caller only needs to know whether one is set.
            result.put("username", Redaction.redactNamed("username",
                    account == null ? null : account.getUsername()));

            var plugin = findPlugin();
            result.put("autoLoginActive", plugin != null && pluginManager.isPluginActive(plugin));
            result.put("attempts", plugin == null ? 0 : readInt(plugin, "getAttempts"));
            result.put("diagnostic", plugin == null ? null : readDiagnostic(plugin));

            return result;
        });
    }

    /**
     * Enables the autologin plugin, which then drives the login from its own scheduler.
     *
     * <p>Restarts it when already running, because the attempt counter only resets in
     * {@code startUp()} - otherwise calling start again after the retry cap was reached would appear
     * to succeed and then do nothing.
     */
    public Map<String, Object> start() throws Exception {
        requirePlugin();
        var plugin = findPlugin();
        if (plugin != null && pluginManager.isPluginActive(plugin)) {
            plugins.setEnabled(PLUGIN_ID, false);
        }
        var entry = plugins.setEnabled(PLUGIN_ID, true);
        var result = new LinkedHashMap<String, Object>();
        result.put("started", Boolean.TRUE.equals(entry.get("active")));
        result.put("pluginId", PLUGIN_ID);
        return result;
    }

    public Map<String, Object> stop() throws Exception {
        requirePlugin();
        plugins.setEnabled(PLUGIN_ID, false);
        var result = new LinkedHashMap<String, Object>();
        result.put("started", false);
        return result;
    }

    /**
     * Stops the autologin driver first. Logging out with it still running means it immediately logs
     * back in, which looks like the logout silently failing.
     */
    public Map<String, Object> logout() throws Exception {
        if (findPlugin() != null) {
            plugins.setEnabled(PLUGIN_ID, false);
        }

        var result = new LinkedHashMap<String, Object>();
        var wasLoggedIn = clientThread.call(() -> Static.getGame().isLoggedIn());
        result.put("submitted", wasLoggedIn);
        if (!Boolean.TRUE.equals(wasLoggedIn)) {
            result.put("loggedOut", true);
            result.put("attempts", 0);
            return result;
        }

        // Drive it to completion rather than firing once. Game.logout() is a single step of a
        // sequence, not a command: with the logout panel unloaded it can only open the tab, because
        // the button's interface (group 182) does not exist until the tab has been opened. The click
        // is therefore only possible on a later tick, and a one-shot call could never log out from a
        // cold panel - it returned "submitted" having done nothing but open a tab.
        var loggedOut = false;
        var attempt = 0;
        while (attempt < LOGOUT_ATTEMPTS && !loggedOut) {
            attempt++;
            clientThread.call(() -> {
                Static.getGame().logout();
                return null;
            });
            Thread.sleep(LOGOUT_POLL_MILLIS);
            loggedOut = !clientThread.call(() -> Static.getGame().isLoggedIn());
        }

        // Report what happened, not what was attempted. The old "submitted" was just wasLoggedIn, so
        // it read true while the client stayed logged in - the failure looked like a success.
        result.put("loggedOut", loggedOut);
        result.put("attempts", attempt);
        return result;
    }

    /** Reads a hook-backed value, falling back when its mapping is missing or stale. */
    private static <T> T safe(java.util.function.Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void requirePlugin() throws ApiCommandException {
        if (findPlugin() == null) {
            throw new ApiCommandException("PLUGIN_NOT_FOUND",
                    "Solace Auto Login is not loaded; check that :bundled is on the classpath");
        }
    }

    private net.solace.api.plugins.Plugin findPlugin() {
        for (var plugin : pluginManager.getPlugins()) {
            if (plugin.getClass().getName().equals(PLUGIN_ID)) {
                return plugin;
            }
        }
        return null;
    }

    private static int readInt(Object target, String method) {
        try {
            return (int) target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return -1;
        }
    }

    private static Map<String, Object> readDiagnostic(Object plugin) {
        try {
            var diagnostic = plugin.getClass().getMethod("getDiagnostic").invoke(plugin);
            if (diagnostic == null) {
                return null;
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("name", invokeString(diagnostic, "getName"));
            result.put("message", invokeString(diagnostic, "getMessage"));
            var code = diagnostic.getClass().getMethod("getCode").invoke(diagnostic);
            result.put("code", code);
            return result;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String invokeString(Object target, String method)
            throws ReflectiveOperationException {
        var value = target.getClass().getMethod(method).invoke(target);
        return value == null ? null : value.toString();
    }

    /** Mirrors {@code net.solace.loader.plugins.autologin.LoginIndex}, which {@code :loader} cannot see. */
    private static String loginIndexName(int index) {
        switch (index) {
            case 0: return "MAIN_MENU";
            case 1: return "BETA_WORLD";
            case 2: return "ENTER_CREDENTIALS";
            case 3: return "INVALID_CREDENTIALS";
            case 4: return "AUTHENTICATOR";
            case 10: return "OAUTH2";
            case 14: return "DISABLED";
            case 24: return "BEEN_DISCONNECTED";
            case 34: return "MEMBERS_REQUIRED";
            default: return "UNKNOWN";
        }
    }
}

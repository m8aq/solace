package net.solace.loader.plugins.controlapi;

import ch.qos.logback.classic.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import net.solace.api.Static;
import net.solace.api.events.PluginChanged;
import net.solace.api.plugins.Plugin;
import net.solace.api.plugins.PluginDescriptor;
import net.solace.api.plugins.PluginManager;
import net.solace.api.plugins.config.ConfigManager;
import net.solace.loader.DevEntry;
import net.solace.loader.controlapi.ApiAccessToken;
import net.solace.loader.controlapi.ApiCommandException;
import net.solace.loader.controlapi.ApiCommandRegistry;
import net.solace.loader.controlapi.ApiServer;
import net.solace.loader.controlapi.ControlApiEndpoints;
import net.solace.loader.controlapi.Params;
import net.solace.loader.controlapi.log.PluginLogAppender;
import net.solace.loader.controlapi.log.PluginLogRoute;
import net.solace.loader.controlapi.log.PluginLogService;
import net.solace.loader.controlapi.service.LoginCommandService;
import net.solace.loader.controlapi.service.PluginCommandService;
import net.solace.loader.controlapi.service.PluginConfigCommandService;
import net.solace.loader.controlapi.service.ScreenshotCommandService;
import net.solace.loader.controlapi.service.ScreenshotRoute;
import net.solace.loader.controlapi.service.StatusCommandService;
import net.solace.loader.controlapi.thread.ClientThreadBridge;
import net.solace.loader.controlapi.thread.EdtBridge;
import net.solace.loader.hotswap.DevPluginHotSwapHolder;
import net.solace.loader.hotswap.DevPluginHotSwapService;
import net.solace.loader.plugins.PluginManagerImpl;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for the loopback control API.
 *
 * <p>This class lives under {@code net.solace.loader.plugins} so {@code
 * PluginManagerImpl.loadCorePlugins()} discovers it by classpath scan. That scan calls {@code
 * Class.forName} on <em>every</em> class in the package tree, and one throwing static initializer
 * takes down all core plugin loading - so everything except this thin entry point and its config
 * lives in {@code net.solace.loader.controlapi}, which is not scanned.
 *
 * <p>Enabled by default, but binds nothing unless {@code -Dsolace.controlapi=true} or {@code
 * SOLACE_CONTROL_API=1}. A config-driven switch would be a footgun - you would need the API to turn
 * the API on - and a production user should never get a listening socket by accident.
 */
@PluginDescriptor(
        name = "Solace Control API",
        description = "Loopback HTTP control plane for driving the client programmatically",
        tags = {"dev", "api", "automation"},
        enabledByDefault = true
)
@Slf4j
public class ControlApiPlugin extends Plugin {
    private static final String ENABLE_PROPERTY = "solace.controlapi";
    private static final String ENABLE_ENV = "SOLACE_CONTROL_API";
    private static final String PORT_PROPERTY = "solace.controlapi.port";

    @Inject
    private ControlApiConfig config;

    private Gson gson;
    private ApiCommandRegistry commands;
    private ApiAccessToken token;
    private ApiServer server;
    private PluginLogService logs;
    private PluginLogAppender appender;

    @Override
    public void startUp() throws Exception {
        if (!isEnabled()) {
            log.debug("[control-api] not starting; set -D{}=true to enable", ENABLE_PROPERTY);
            return;
        }

        // Before anything else reads the directory: nothing else deletes these, so without a reap it
        // accumulates one file per dev run and cannot be trusted to say which client is live - which
        // is exactly what a bind failure needs it for.
        ControlApiEndpoints.reapStale();

        try {
            // Deliberately not RuneLiteAPI.GSON: that instance also serializes the config profile
            // store, and a permissive API deserializer must never influence how config is written.
            gson = new GsonBuilder().serializeNulls().create();
            commands = new ApiCommandRegistry();
            token = new ApiAccessToken();

            var startedAt = Instant.now();
            var status = new StatusCommandService(
                    () -> server == null ? resolvePort() : server.getPort(),
                    () -> commands.names().size(),
                    startedAt);

            commands.register("client.status", params -> status.status());
            // Also served by GET /api/commands; registered so a client that only speaks
            // POST /api/command can discover the surface too.
            commands.register("commands.list", params -> {
                var result = new LinkedHashMap<String, Object>();
                result.put("commands", commands.names());
                result.put("count", commands.names().size());
                return result;
            });

            var edt = new EdtBridge(config.edtTimeoutMillis());
            var plugins = new PluginCommandService(pluginManager(), edt);
            commands.register("plugins.list", params -> plugins.list());
            commands.register("plugins.get", params ->
                    plugins.get(Params.requiredString(params, "pluginId")));
            commands.register("plugins.setEnabled", params -> plugins.setEnabled(
                    Params.requiredString(params, "pluginId"),
                    Params.requiredBoolean(params, "enabled")));
            commands.register("plugins.restart", params ->
                    plugins.restart(Params.requiredString(params, "pluginId")));

            var configs = new PluginConfigCommandService(
                    Static.getPluginManager(), Static.getConfigManager(), plugins, gson, edt);
            commands.register("config.list", params ->
                    configs.list(Params.requiredString(params, "pluginId")));
            commands.register("config.get", params -> configs.get(
                    Params.requiredString(params, "pluginId"),
                    Params.requiredString(params, "key")));
            commands.register("config.set", params -> configs.set(
                    Params.requiredString(params, "pluginId"),
                    Params.requiredString(params, "key"),
                    Params.required(params, "value")));
            commands.register("config.unset", params -> configs.unset(
                    Params.requiredString(params, "pluginId"),
                    Params.requiredString(params, "key")));

            logs = new PluginLogService(pluginManager(), config.logHistoryLimit());
            commands.register("logs.tail", params -> logs.tail(
                    Params.requiredString(params, "pluginId"),
                    Params.optionalInt(params, "limit", 100)));

            var clientThread = new ClientThreadBridge(
                    Static.getClientThread(), config.commandTimeoutMillis());
            var login = new LoginCommandService(plugins, pluginManager(), clientThread);
            commands.register("login.status", params -> login.status());
            commands.register("login.start", params -> login.start());
            commands.register("login.stop", params -> login.stop());
            commands.register("login.logout", params -> login.logout());

            // Resolved per call, never captured. This plugin is started by loadCorePlugins() during
            // DevEntry.start(), which finishes before the holder is populated - capturing here would
            // snapshot a permanent null and report hot-swapping as disabled while it runs.
            // Null in production, where hot-swapping is not configured at all.
            commands.register("hotswap.status", params -> {
                var hotSwap = DevPluginHotSwapHolder.get();
                return hotSwap == null
                        ? Map.of("enabled", false,
                            "reason", "start with -D" + DevPluginHotSwapService.JAR_PROPERTY + "=<path>")
                        : hotSwap.status();
            });
            commands.register("hotswap.reload", params -> {
                var hotSwap = DevPluginHotSwapHolder.get();
                if (hotSwap == null) {
                    throw new ApiCommandException("HOTSWAP_DISABLED",
                            "Hot-swapping is not configured; set -D"
                                    + DevPluginHotSwapService.JAR_PROPERTY);
                }
                return hotSwap.reloadNow();
            });

            // Fire-and-forget, necessarily. This handler runs on a thread the reload is about to
            // shut down - tearing down the layer closes this very server and calls shutdownNow() on
            // its pool - so it cannot wait for the outcome. The bootstrap's endpoint is outside the
            // layer and survives, which is where a caller polls for the result.
            commands.register("layer.reload", params -> {
                var request = DevEntry.getReloadRequest();
                if (request == null) {
                    throw new ApiCommandException("LAYER_RELOAD_UNAVAILABLE",
                            "Not running under the reloadable bootstrap; launch via :devboot:runDev");
                }
                var thread = new Thread(request, "solace-layer-reload-request");
                thread.setDaemon(true);
                thread.start();

                return Map.of(
                        "accepted", true,
                        "note", "the layer is reloading; this server is torn down as part of it",
                        "pollAt", "http://127.0.0.1:"
                                + System.getProperty("solace.reload.port", "7781") + "/status");
            });

            var screenshots = new ScreenshotCommandService(config.screenshotTimeoutMillis());
            commands.register("screenshot", params -> screenshots.captureAsJson(
                    Params.optionalInt(params, "timeoutMs", 0),
                    Params.optionalInt(params, "maxWidth", 0),
                    "frame".equals(Params.optionalString(params, "include", "canvas"))));

            server = new ApiServer(gson, commands, token, resolvePort());
            server.addContext(PluginLogRoute.PATH, new PluginLogRoute(server, logs, gson));
            server.addContext(ScreenshotRoute.PATH, new ScreenshotRoute(server, screenshots));
            server.start();
            token.publish(gson, server.getPort());

            attachLogAppender();
        } catch (Exception e) {
            // Before shutDown() clears the fields the message needs.
            if (e instanceof BindException) {
                reportStaleApi((BindException) e);
            }
            // Leave nothing half-built - a failed bind must not leave a plugin claiming to be active.
            shutDown();
            throw e;
        }
    }

    /**
     * Says, at ERROR, that the port is serving somebody else's code.
     *
     * <p>Without this the only trace is {@code PluginManagerImpl}'s generic "Failed to start plugin",
     * which is indistinguishable from any other plugin failing and says nothing about the consequence.
     * And the consequence is the whole problem: the port keeps answering, {@code commands.list} keeps
     * returning a plausible list, and every command dispatched there runs <em>stale code</em>. A silent
     * downgrade to "hot reload quietly stopped working" is the worst outcome available here, so this is
     * deliberately loud and deliberately spells out what to do about it.
     */
    private void reportStaleApi(BindException e) {
        var port = resolvePort();
        log.error("[control-api] ======================= THE CONTROL API IS NOT RUNNING =======================");
        log.error("[control-api] {}", e.getMessage());
        log.error("[control-api] Anything answering on 127.0.0.1:{} belongs to that other process, NOT", port);
        log.error("[control-api] to the layer that just started. commands.list and every command you");
        log.error("[control-api] send there reflect STALE CODE - edits you just built will appear to");
        log.error("[control-api] have had no effect.");
        log.error("[control-api] Fix: stop the process holding the port, or start this client with a");
        log.error("[control-api] different one (-D{}=<port>, or scripts/run-dev.sh --port <port>).",
                PORT_PROPERTY);
        log.error("[control-api] ==============================================================================");
    }

    /**
     * Taps the root logger, but only when logback is actually the bound SLF4J backend - the cast is
     * otherwise a {@link ClassCastException} that would take the whole plugin down over a
     * nice-to-have.
     */
    private void attachLogAppender() {
        var root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (!(root instanceof Logger)) {
            log.warn("[control-api] root logger is {}, not logback - log streaming disabled",
                    root.getClass().getName());
            return;
        }
        var logbackRoot = (Logger) root;
        appender = new PluginLogAppender(logs);
        appender.setContext(logbackRoot.getLoggerContext());
        appender.start();
        logbackRoot.addAppender(appender);
    }

    @Subscribe
    public void onPluginChanged(PluginChanged event) {
        if (!event.isLoaded() && logs != null) {
            logs.pluginStopped(event.getPlugin().getClass().getName(), "disabled");
        }
    }

    @Override
    public void shutDown() {
        if (appender != null) {
            var root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            if (root instanceof Logger) {
                ((Logger) root).detachAppender(appender);
            }
            appender.stop();
            appender = null;
        }
        if (logs != null) {
            logs.close();
            logs = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        if (token != null) {
            token.unpublish();
            token = null;
        }
        if (commands != null) {
            commands.clear();
            commands = null;
        }
        gson = null;
    }

    private static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY) || "1".equals(System.getenv(ENABLE_ENV));
    }

    /**
     * The concrete manager, for {@code isPluginActive} which is not on the {@link PluginManager}
     * interface. Safe to cast: {@code LoaderModule.providePluginManager} binds the interface to a
     * singleton {@code PluginManagerImpl}. Resolving {@code PluginManagerImpl.class} from Guice
     * instead would mint a second, JIT-bound instance holding a different plugin list.
     */
    private static PluginManagerImpl pluginManager() {
        var manager = Static.getPluginManager();
        if (!(manager instanceof PluginManagerImpl)) {
            throw new IllegalStateException(
                    "Expected PluginManagerImpl but got " + manager.getClass().getName());
        }
        return (PluginManagerImpl) manager;
    }

    /**
     * System property wins over config so {@code runDev} and {@code run-dev.sh} can set the port
     * without touching the operator's profile.
     */
    private int resolvePort() {
        var override = System.getProperty(PORT_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException e) {
                log.warn("[control-api] -D{}={} is not a number; falling back to config",
                        PORT_PROPERTY, override);
            }
        }
        return config.port();
    }

    @Provides
    ControlApiConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ControlApiConfig.class);
    }
}

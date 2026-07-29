package net.solace.loader.plugins.autologin;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.World;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.eventbus.Subscribe;
import net.solace.api.Static;
import net.solace.api.plugins.LoopedPlugin;
import net.solace.api.plugins.PluginDescriptor;
import net.solace.api.plugins.config.ConfigManager;
import net.solace.api.game.Client;
import net.solace.api.game.Game;
import net.solace.api.game.Worlds;
import net.solace.api.input.Keyboard;
import net.solace.api.script.blocking_events.LoginEvent;
import net.solace.api.script.blocking_events.WelcomeScreenEvent;
import net.solace.api.widgets.Widgets;

/**
 * Logs the client in from the login screen.
 *
 * <p>Runs as a {@link LoopedPlugin}, so {@code LoopedPluginManager} drives it on a dedicated thread
 * and each attempt is marshalled onto the client thread. That matters here specifically: the client
 * posts no {@code ClientTick} while it sits on the login screen, so a tick-driven driver stalls there.
 * An owned thread does not.
 *
 * <p>Terminal failures park the plugin rather than stopping it. Retrying invalid credentials only
 * burns attempts against the login limit, but staying loaded is what lets the control API's
 * {@code login.status} report <em>why</em> a login failed instead of showing a plugin that vanished.
 */
@PluginDescriptor(
        name = "Solace Auto Login",
        description = "Logs in from the login screen and clicks through the welcome screen",
        tags = {"login", "auto", "account"},
        enabledByDefault = true
)
@Slf4j
public class SolaceAutoLoginPlugin extends LoopedPlugin {
    /** Poll delay while there is nothing to do - not on the login screen, or parked. */
    private static final int IDLE_SLEEP_MILLIS = 600;

    @Inject
    private SolaceAutoLoginConfig config;

    @Getter
    @Setter
    private int loginAttempts = 0;

    @Getter
    @Setter
    private String shutdownMessage = "";

    /** Set once a terminal failure is seen; cleared on the next successful login. */
    private volatile boolean parked;

    /** Last failure reason, or null. Read reflectively by the control API's {@code login.status}. */
    @Getter
    private volatile Diagnostic diagnostic;

    @Provides
    public SolaceAutoLoginConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(SolaceAutoLoginConfig.class);
    }

    @Override
    public void startUp() {
        setLoginAttempts(0);
        setShutdownMessage("");
        parked = false;
        diagnostic = null;

        var gameAccount = Game.getGameAccount();
        if (gameAccount != null) {
            if (gameAccount.isJagexLauncher()) {
                Static.getClient().setSessionId(gameAccount.getUsername());
                Static.getClient().setCharacterId(gameAccount.getPassword());
                Static.getClient().setDisplayName(gameAccount.getDisplayName());
                return;
            }

            Static.getClient().setSessionId(null);
            Static.getClient().setCharacterId(null);
            Static.getClient().setDisplayName(null);
            config.username(gameAccount.getUsername());
            config.password(gameAccount.getPassword());
        }
    }

    @Override
    public int loop() {
        if (!isOnLoginScreen() || parked) {
            return IDLE_SLEEP_MILLIS;
        }

        if (config.maxRetries() > 0 && getLoginAttempts() >= config.maxRetries()) {
            park("MAX_ATTEMPTS", "Gave up after " + getLoginAttempts() + " attempts");
            return IDLE_SLEEP_MILLIS;
        }

        closeWorldSelect();

        Static.getGameThread().invokeAndWait(() -> {
            handleLoginScreen(Client.getWrapped().getLoginIndex());
            return null;
        });

        return retryDelay();
    }

    @Subscribe
    private void onWidgetLoaded(WidgetLoaded e) {
        if (!config.welcomeScreen()) {
            return;
        }

        var group = e.getGroupId();
        if (group == 378 || group == 413) {
            var playButton = WelcomeScreenEvent.getPlayButton();
            if (Widgets.isVisible(playButton)) {
                Client.invokeWidgetAction(1, playButton.getId(), -1, -1, "");
            }
        }
    }

    @Subscribe
    private void onGameStateChanged(GameStateChanged e) {
        var state = e.getGameState();
        if (state == GameState.LOGGED_IN) {
            setLoginAttempts(0);
            parked = false;
            diagnostic = null;
        } else if (state == GameState.LOGIN_SCREEN) {
            // Re-arm on every return to the login screen - logout, or a disconnect.
            parked = false;
        }
    }

    /** Runs on the client thread. */
    private void handleLoginScreen(int loginIndex) {
        setLoginAttempts(getLoginAttempts() + 1);
        recordResponse();

        switch (loginIndex) {
            case LoginIndex.OAUTH2:
                log.info("OAuth2 login");
                jlLogin();
                return;
            case LoginIndex.AUTHENTICATOR:
                // Two-factor accounts are not supported: nothing here can produce the code. Park with
                // a clear reason rather than retrying a screen that can never advance.
                park("AUTHENTICATOR_REQUIRED", "Account requires an authenticator code");
                return;
            case LoginIndex.DISABLED:
                park("DISABLED", "Account banned");
                return;
            case LoginIndex.INVALID_CREDENTIALS:
                park("INVALID_CREDENTIALS", "Invalid credentials");
                return;
            case LoginIndex.MEMBERS_REQUIRED:
                park("MEMBERS_REQUIRED", "Members required");
                return;
            default:
                prepareLogin(loginIndex);
                enterCredentials();
        }
    }

    /**
     * Moves off the "New User / Existing User" main menu onto the credentials screen.
     *
     * <p>Gated to the screens that actually need advancing: re-running it once the credentials screen
     * is already up clears the fields on every attempt, so it must not fire unconditionally.
     *
     * <p>{@link LoginIndex#BEEN_DISCONNECTED} needs it as much as {@link LoginIndex#MAIN_MENU} does.
     * That is the screen a timeout logout leaves you on, and it has no credential fields - so without
     * advancing off it, {@link #enterCredentials()} typed into nothing and every relog after an idle
     * disconnect burned attempts until the retry cap, which is what made timeout relog look broken.
     *
     * <p>{@code promptCredentials} is best-effort. Its hook ({@code Login_promptCredentials}) is not
     * mapped on every revision - it is absent on 1.12.33 - and it only clears the field contents that
     * {@link #enterCredentials()} is about to overwrite. {@code setLoginIndex} is the call that
     * actually moves the screen, and that hook is mapped.
     */
    private void prepareLogin(int loginIndex) {
        if (config.useWorld() && Client.getWorld() != config.world()) {
            World world = Worlds.getFirst(config.world());
            if (world != null) {
                Client.changeWorld(world);
                return;
            }
        }

        if (loginIndex != LoginIndex.MAIN_MENU && loginIndex != LoginIndex.BEEN_DISCONNECTED) {
            return;
        }

        try {
            Client.promptCredentials(false);
        } catch (RuntimeException e) {
            log.debug("promptCredentials unavailable on this revision: {}", e.getMessage());
        }

        Client.setLoginIndex(isOAuthConfigured() ? LoginIndex.OAUTH2 : LoginIndex.ENTER_CREDENTIALS);
    }

    /**
     * Best-effort. {@code worldSelectOpen} is not mapped on every revision - it is absent on 1.12.33 -
     * and letting it throw aborted the whole loop before any login logic ran, on every iteration. That
     * was the plugin's actual failure mode: it looked idle while spinning on this exception.
     */
    private void closeWorldSelect() {
        try {
            if (Client.isWorldSelectOpen()) {
                Client.setWorldSelectOpen(false);
            }
        } catch (RuntimeException e) {
            log.debug("worldSelectOpen unavailable on this revision: {}", e.getMessage());
        }
    }

    private boolean isOAuthConfigured() {
        try {
            return Client.isOAuthCredentialsSet();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void enterCredentials() {
        var username = username();
        var password = password();
        if (username.isEmpty() || password.isEmpty()) {
            park("NO_CREDENTIALS", "No credentials configured");
            return;
        }

        Client.setNormalLoginMode();
        Client.setUsername(username);
        Client.setPassword(password);
        Keyboard.sendEnter();
        Keyboard.sendEnter();
        log.info("Submitted login attempt {}", getLoginAttempts());
    }

    private void jlLogin() {
        Client.setOAuthLoginMode();
        Client.setGameState(GameState.LOGGING_IN);
    }

    /**
     * When {@code --account} was supplied, the loader's {@code EventManager} already writes those
     * credentials on every {@code GameStateChanged}. Sourcing from the same place makes both writers
     * agree instead of racing.
     */
    private String username() {
        var account = config.useGameAccount() ? Game.getGameAccount() : null;
        var value = account != null ? account.getUsername() : config.username();
        return value == null ? "" : value;
    }

    private String password() {
        var account = config.useGameAccount() ? Game.getGameAccount() : null;
        var value = account != null ? account.getPassword() : config.password();
        return value == null ? "" : value;
    }

    /**
     * Snapshots why the last attempt failed. Every failure is swallowed: {@code getLoginMessage()}
     * reads through a reflection hook from {@code mappings/version-package.json}, and on the current
     * revision that hook is missing ("Field not found: loginResponse1"). Letting that propagate would
     * abort the login attempt before it ran - the observability breaking the thing it observes.
     */
    private void recordResponse() {
        try {
            var message = Client.getLoginMessage();
            if (message == null || message.trim().isEmpty()) {
                return;
            }
            var response = LoginEvent.Response.forMessage(message);
            if (response != null) {
                diagnostic = new Diagnostic(response.name(), response.getCode(), message);
            }
        } catch (RuntimeException e) {
            log.debug("login message unavailable: {}", e.getMessage());
        }
    }

    /**
     * Stops attempting without stopping the plugin, so {@code login.status} can still say why. Cleared
     * by a return to the login screen or a successful login.
     */
    private void park(String name, String reason) {
        log.warn("Auto login giving up: {}", reason);
        diagnostic = new Diagnostic(name, -1, reason);
        setShutdownMessage(reason);
        parked = true;
    }

    private int retryDelay() {
        return Math.max(500, config.retryDelayMillis());
    }

    private boolean isOnLoginScreen() {
        var state = Game.getState();
        return state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR;
    }

    /** Read reflectively by the control API's {@code login.status}. */
    public int getAttempts() {
        return getLoginAttempts();
    }

    /** Why the last login attempt failed. */
    @Getter
    public static final class Diagnostic {
        private final String name;
        private final int code;
        private final String message;

        Diagnostic(String name, int code, String message) {
            this.name = name;
            this.code = code;
            this.message = message;
        }
    }
}

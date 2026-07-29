package net.solace.loader.plugins.solacedevtools;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayManager;
import net.solace.api.plugins.Plugin;
import net.solace.api.plugins.PluginDescriptor;
import net.solace.api.plugins.config.ConfigManager;
import net.solace.loader.plugins.menurecorder.MenuRecorder;
import net.solace.loader.plugins.solacedevtools.logger.LoggerWindow;

@PluginDescriptor(
        name = "Solace Dev Tools",
        description = "A collection of tools for developers",
        tags = {"dev", "tools"}
)
@Slf4j
public class SolaceDevToolsPlugin extends Plugin {
    @Inject
    private SolaceDevToolsConfig config;

    @Inject
    private SolaceDevToolsOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private EventBus eventBus;

    @Inject
    private Client client;

    private LoggerWindow logger;

    /** Non-null only while "Log menu interactions" is on. */
    private MenuRecorder menuRecorder;

    @Override
    public void startUp() {
        overlayManager.add(overlay);
        eventBus.register(overlay);

        if (logger == null) {
            logger = new LoggerWindow();
        }

        logger.setVisible(true);
    }

    @Override
    public void shutDown() {
        overlayManager.remove(overlay);
        eventBus.unregister(overlay);

        logger.dispose();
        stopMenuRecorder();
    }

    /** The high-value hook: one right-click emits every op the client built for the entity. */
    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        var recorder = activeMenuRecorder();
        var entries = event.getMenuEntries();
        if (recorder == null || entries == null) {
            return;
        }
        for (var entry : entries) {
            recorder.observe(entry, "MENU_OPENED");
        }
    }

    /** Catches left-click ops, which never open a menu. */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        var recorder = activeMenuRecorder();
        if (recorder != null) {
            recorder.observe(event.getMenuEntry(), "CLICKED");
        }
    }

    /**
     * Starts the recorder on the first event after the toggle goes on, and writes its summary when the
     * toggle goes off. Polling the config here rather than subscribing to ConfigChanged keeps the
     * lifecycle in one place, and costs a proxy call on events the client already fires per click.
     */
    private MenuRecorder activeMenuRecorder() {
        if (!config.logMenuInteractions()) {
            stopMenuRecorder();
            return null;
        }
        if (menuRecorder == null) {
            menuRecorder = new MenuRecorder(client);
            menuRecorder.start();
        }
        return menuRecorder;
    }

    private void stopMenuRecorder() {
        if (menuRecorder != null) {
            menuRecorder.stop();
            menuRecorder = null;
        }
    }

    @Provides
    public SolaceDevToolsConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(SolaceDevToolsConfig.class);
    }
}

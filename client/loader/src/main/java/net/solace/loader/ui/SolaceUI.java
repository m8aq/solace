package net.solace.loader.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.solace.api.plugins.config.PluginListPanel;
import net.solace.ui.plugins.TopLevelConfigPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

@Singleton
@Slf4j
public class SolaceUI {
    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private PluginListPanel pluginListPanel;

    @Inject
    private TopLevelConfigPanel topLevelConfigPanel;

    NavigationButton navButton;

    /**
     * Guards the deferred {@code openPanel} below. Without it, a teardown that runs before the queued
     * lambda re-attaches a dead panel to the sidebar - a nav button pointing at a Swing component from
     * the outgoing classloader, which pins that whole generation.
     */
    private volatile boolean live;

    public void init() {
        var icon = ImageUtil.loadImageResource(getClass(), "solace.png");
        navButton = NavigationButton.builder()
                .tooltip("Solace")
                .icon(icon)
                .priority(-1)
                .panel(topLevelConfigPanel)
                .build();

        live = true;
        pluginListPanel.rebuildPluginList();
        clientToolbar.addNavigation(navButton);
        SwingUtilities.invokeLater(() -> {
            if (live) {
                clientToolbar.openPanel(navButton);
            }
        });
    }

    public void clear() {
        live = false;

        // init() may never have run, or may have thrown partway - removeNavigation(null) NPEs.
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        try {
            topLevelConfigPanel.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close the Solace panel", e);
        }
    }
}

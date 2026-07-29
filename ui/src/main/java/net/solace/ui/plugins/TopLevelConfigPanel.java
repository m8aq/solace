/*
 * Copyright (c) 2023 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.solace.ui.plugins;

import com.google.inject.Provider;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.solace.api.plugins.Plugin;
import net.solace.api.plugins.config.ConfigManager;
import net.solace.api.plugins.config.ConfigPanel;
import net.solace.api.plugins.config.PluginConfigurationDescriptor;
import net.solace.api.plugins.config.PluginListPanel;
import net.solace.api.plugins.config.SolaceConfig;
import net.solace.loader.config.SolaceProperties;
import net.solace.ui.sdn.SdnPluginManagerPanel;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridLayout;

public class TopLevelConfigPanel extends PluginPanel {
    // Not @Slf4j: PluginPanel extends java.awt.Container, which has a private `log` field that
    // shadows the generated one at every use site.
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TopLevelConfigPanel.class);

    private final MaterialTabGroup tabGroup;
    private final CardLayout layout;
    private final JPanel content;
    private final JPanel footer;

    private final EventBus eventBus;

    /**
     * Every panel handed to {@code eventBus.register}, so {@link #close()} can undo it. RuneLite's
     * EventBus builds a LambdaMetafactory hidden class per subscriber, defined in the subscriber's
     * nest - a single missed unregister pins the panel's entire classloader generation.
     */
    private final java.util.List<PluginPanel> registered = new java.util.ArrayList<>();
    private final PluginListPanel pluginListPanel;
    private final SdnPluginManagerPanel sdnPanel;
    private final Provider<ConfigPanel> configPanelProvider;

    private final MaterialTab pluginListPanelTab;
    private final PluginConfigurationDescriptor solaceConfigDescriptor;

    private boolean active = false;
    private PluginPanel current;
    private boolean removeOnTabChange;

    public TopLevelConfigPanel(
            EventBus eventBus,
            PluginListPanel pluginListPanel,
            SdnPluginManagerPanel sdnPanel,
            Provider<ConfigPanel> configPanelProvider,
            SolaceConfig solaceConfig,
            ConfigManager configManager,
            ProfilePanel profilePanel
    ) {
        super(false);

        this.eventBus = eventBus;
        this.pluginListPanel = pluginListPanel;
        this.sdnPanel = sdnPanel;
        this.configPanelProvider = configPanelProvider;

        tabGroup = new MaterialTabGroup();
        tabGroup.setLayout(new GridLayout(1, 0, 7, 7));
        tabGroup.setBorder(new EmptyBorder(10, 10, 0, 10));

        content = new JPanel();
        layout = new CardLayout();
        content.setLayout(layout);

        footer = new JPanel();
        footer.setLayout(new BorderLayout());
        footer.setBorder(new EmptyBorder(6, 10, 8, 10));
        var runeLiteVersion = SolaceProperties.RUNELITE_VERSION;
        var solaceVersion = SolaceProperties.LOADER_VERSION;
        var footerLabel = new JLabel("Solace " + solaceVersion + " for RuneLite " + runeLiteVersion);
        footerLabel.setHorizontalAlignment(JLabel.CENTER);
        footer.add(footerLabel, BorderLayout.NORTH);

        // A dev build sets no COMMIT_SHA, so the property is the literal string "unknown" - showing
        // that under the version reads as a bug rather than as an absent build stamp.
        var hash = SolaceProperties.COMMIT_HASH;
        if (hash != null && !hash.isBlank() && !"unknown".equals(hash)) {
            var hashLabel = new JLabel(hash.length() > 7 ? hash.substring(0, 7) : hash);
            hashLabel.setHorizontalAlignment(JLabel.CENTER);
            footer.add(hashLabel, BorderLayout.CENTER);
        }

        var proxyHost = System.getProperty("socksProxyHost");
        var proxyPort = System.getProperty("socksProxyPort");
        if (proxyHost != null && proxyPort != null) {
            var proxyInfo = new JLabel("Proxy: " + proxyHost + ":" + proxyPort);
            proxyInfo.setHorizontalAlignment(JLabel.CENTER);
            footer.add(proxyInfo, BorderLayout.SOUTH);
        }

        setLayout(new BorderLayout());
        add(tabGroup, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        solaceConfigDescriptor = new PluginConfigurationDescriptor(
                "Solace Settings",
                "Solace settings",
                new String[]{"solace"},
                solaceConfig,
                configManager.getConfigDescriptor(solaceConfig)
        );

        pluginListPanelTab = addTabIcon(pluginListPanel.getMuxer(), "plugin.png", "My plugins");
        addSolaceConfigTab();
        addTabIcon(sdnPanel, "download.png", "Download plugins");
        addTabIcon(profilePanel, "profiles.png", "Profiles");

        tabGroup.select(pluginListPanelTab);
    }

    private MaterialTab addTab(PluginPanel panel, String text, String tooltip) {
        var mt = new MaterialTab(text, tabGroup, null);
        mt.setToolTipText(tooltip);
        tabGroup.addTab(mt);

        content.add(text, panel.getWrappedPanel());
        eventBus.register(panel);
        registered.add(panel);

        mt.setOnSelectEvent(() ->
        {
            switchTo(text, panel);
            return true;
        });
        return mt;
    }

    private MaterialTab addTabIcon(PluginPanel panel, String image, String tooltip) {
        var mt = new MaterialTab(
                new ImageIcon(ImageUtil.loadImageResource(TopLevelConfigPanel.class, image)),
                tabGroup,
                null
        );
        mt.setToolTipText(tooltip);
        tabGroup.addTab(mt);

        content.add(image, panel.getWrappedPanel());
        eventBus.register(panel);
        registered.add(panel);

        mt.setOnSelectEvent(() ->
        {
            switchTo(image, panel);
            return true;
        });
        return mt;
    }

    private void addSolaceConfigTab() {
        var mt = new MaterialTab(
                new ImageIcon(ImageUtil.loadImageResource(TopLevelConfigPanel.class, "settings.png")),
                tabGroup,
                null
        );
        mt.setToolTipText("Solace settings");
        tabGroup.addTab(mt);

        mt.setOnSelectEvent(() -> {
            var solaceConfigPanel = configPanelProvider.get();
            solaceConfigPanel.init(solaceConfigDescriptor, false);
            content.add("Solace settings", solaceConfigPanel);
            switchTo("Solace settings", solaceConfigPanel);
            return true;
        });
    }

    /** Detaches every tab panel from the event bus. Called from {@code SolaceUI.clear()}. */
    public void close() {
        for (var panel : registered) {
            try {
                eventBus.unregister(panel);
            } catch (RuntimeException e) {
                LOG.warn("Failed to unregister panel {}", panel.getClass().getName(), e);
            }
        }
        registered.clear();

        // The muxer holds every config panel ever opened, each registered by its onAdd hook and only
        // unregistered by onRemove - which teardown never triggers. destroy() walks the stack calling
        // onRemove on each, and is the ONLY way to detach them: MultiplexingPluginPanel has no
        // close(), so the reflective closeQuietly() probe below silently did nothing here and every
        // ConfigPanelImpl stayed on the event bus, pinning its whole classloader generation.
        try {
            pluginListPanel.getMuxer().destroy();
        } catch (RuntimeException e) {
            LOG.warn("Failed to destroy the plugin list muxer", e);
        }

        // Some panels register themselves as well as being registered here (PluginListPanelImpl via
        // the muxer, PluginsPanel in its constructor). Unregistering the tab is not enough for those,
        // so give anything with a close() the chance to detach itself.
        for (var panel : new Object[]{pluginListPanel, sdnPanel}) {
            closeQuietly(panel);
        }

        // switchTo() may have swapped in a panel that was never in `registered`.
        if (current != null) {
            try {
                eventBus.unregister(current);
            } catch (RuntimeException e) {
                LOG.warn("Failed to unregister the active panel", e);
            }
        }
    }

    private void switchTo(String cardName, PluginPanel panel) {
        var doRemove = this.removeOnTabChange;
        var prevPanel = current;
        if (active) {
            prevPanel.onDeactivate();
            panel.onActivate();
        }

        current = panel;
        this.removeOnTabChange = false;

        layout.show(content, cardName);

        if (doRemove) {
            content.remove(prevPanel.getWrappedPanel());
            eventBus.unregister(prevPanel);
        }

        content.revalidate();
    }

    @Override
    public void onActivate() {
        active = true;
        current.onActivate();
    }

    @Override
    public void onDeactivate() {
        active = false;
        current.onDeactivate();
    }

    public void openConfigurationPanel(String name) {
        tabGroup.select(pluginListPanelTab);
        pluginListPanel.openConfigurationPanel(name);
    }

    public void openConfigurationPanel(Plugin plugin) {
        tabGroup.select(pluginListPanelTab);
        pluginListPanel.openConfigurationPanel(plugin);
    }

    private static void closeQuietly(Object panel) {
        if (panel == null) {
            return;
        }
        try {
            panel.getClass().getMethod("close").invoke(panel);
        } catch (ReflectiveOperationException e) {
            // No close() to call - nothing to do.
        }
    }
}

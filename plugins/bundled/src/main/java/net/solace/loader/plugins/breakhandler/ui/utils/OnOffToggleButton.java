package net.solace.loader.plugins.breakhandler.ui.utils;

import net.runelite.client.util.SwingUtil;
import net.solace.api.ui.Switcher;

import javax.swing.JToggleButton;
import java.awt.Dimension;

public class OnOffToggleButton extends JToggleButton {
    public OnOffToggleButton() {
        Switcher.apply(this);
        setPreferredSize(new Dimension(25, 0));
        SwingUtil.addModalTooltip(this, "Disable", "Enable");
    }
}

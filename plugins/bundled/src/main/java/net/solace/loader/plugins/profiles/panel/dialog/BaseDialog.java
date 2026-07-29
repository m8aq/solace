package net.solace.loader.plugins.profiles.panel.dialog;

import net.runelite.client.ui.ColorScheme;
import net.solace.api.ui.InputStyle;

import javax.swing.JDialog;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;

public abstract class BaseDialog extends JDialog {
    protected final JPanel mainPanel;
    protected final JPanel buttonPanel;

    protected BaseDialog(Frame owner, String title) {
        super(owner, title, true);
        setLayout(new BorderLayout());

        mainPanel = new JPanel();
        buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        add(mainPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    protected void finishDialog() {
        // Theme the whole tree rather than just mainPanel/buttonPanel. Each subclass builds its own
        // nested panels of labels and fields, so styling only the two panels this class owns would
        // leave everything inside them rendering as default Metal against the dark client.
        InputStyle.themeTree(getContentPane(), ColorScheme.DARKER_GRAY_COLOR);
        pack();
        setLocationRelativeTo(getOwner());
    }

}

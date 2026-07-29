package net.solace.api.plugins.config;

import java.awt.Dimension;
import java.util.List;
import javax.swing.JToggleButton;
import net.solace.api.ui.Switcher;

public class PluginToggleButton
extends JToggleButton {
    private String conflictString = "";

    public PluginToggleButton() {
        Switcher.apply(this);
        this.setPreferredSize(new Dimension(25, 0));
        this.addItemListener(l -> this.updateTooltip());
        this.updateTooltip();
    }

    private void updateTooltip() {
        this.setToolTipText((String)(this.isSelected() ? "Disable plugin" : "<html>Enable plugin" + this.conflictString));
    }

    public void setConflicts(List<String> conflicts) {
        if (conflicts != null && !conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder("<br>Plugin conflicts: ");
            for (int i = 0; i < conflicts.size() - 2; ++i) {
                sb.append(conflicts.get(i));
                sb.append(", ");
            }
            if (conflicts.size() >= 2) {
                sb.append(conflicts.get(conflicts.size() - 2));
                sb.append(" and ");
            }
            sb.append(conflicts.get(conflicts.size() - 1));
            this.conflictString = sb.toString();
        } else {
            this.conflictString = "";
        }
        this.updateTooltip();
    }

}


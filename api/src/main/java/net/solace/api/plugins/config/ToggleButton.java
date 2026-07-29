package net.solace.api.plugins.config;

import javax.swing.JCheckBox;
import net.runelite.client.util.Text;
import net.solace.api.ui.Switcher;

public class ToggleButton
extends JCheckBox {
    private final Object object;

    public ToggleButton() {
        this.object = null;
        Switcher.apply(this);
    }

    public ToggleButton(String text) {
        super(text);
        this.object = null;
        Switcher.apply(this);
    }

    public ToggleButton(Object object) {
        super(Text.titleCase((Enum)((Enum)object)));
        this.object = object;
        Switcher.apply(this);
    }

    public Object getObject() {
        return this.object;
    }

}


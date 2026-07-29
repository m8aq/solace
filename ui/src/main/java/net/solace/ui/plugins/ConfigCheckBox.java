package net.solace.ui.plugins;

import lombok.Getter;
import net.runelite.client.util.Text;
import net.solace.api.ui.Switcher;

import javax.swing.JCheckBox;

@Getter
public class ConfigCheckBox extends JCheckBox {
    private final Object object;

    public ConfigCheckBox(Object object) {
        super(Text.titleCase((Enum<?>) object));
        this.object = object;
        Switcher.apply(this);
    }
}

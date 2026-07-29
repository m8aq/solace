package net.solace.api.ui;

import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;

import javax.swing.AbstractButton;
import javax.swing.ImageIcon;

/**
 * The on/off switcher used by every toggle in the client.
 *
 * <p>Shared as a static utility over {@link AbstractButton} rather than as a base class, because the
 * toggles do not agree on a superclass - the plugin list needs a {@code JToggleButton}, the config
 * panel needs a {@code JCheckBox} (its change handlers branch on {@code instanceof JCheckBox}), and
 * the break handler has its own. All four icon setters are declared on {@code AbstractButton}, so a
 * utility covers every case without touching any hierarchy.
 *
 * <p>This replaces four near-duplicate implementations that had each drifted: three derived the OFF
 * state by grayscaling and flipping the ON image instead of using the artwork, two had no disabled
 * state at all, and one loaded its own copy of the PNGs from a third resource tree.
 */
public final class Switcher {
    /** Icon-text gap for switchers that carry a label, e.g. the enum-set rows. */
    private static final int TEXT_GAP = 6;

    public static final ImageIcon ON;
    public static final ImageIcon OFF;
    public static final ImageIcon DISABLED;

    private Switcher() {
    }

    /**
     * Applies the switcher look to a button: the three icons, no LAF chrome, consistent text gap.
     * Returns the button so it can be used inline.
     */
    public static <T extends AbstractButton> T apply(T button) {
        button.setIcon(OFF);
        button.setSelectedIcon(ON);
        button.setDisabledIcon(DISABLED);
        SwingUtil.removeButtonDecorations(button);
        button.setIconTextGap(TEXT_GAP);
        return button;
    }

    static {
        // Resolved against this class, so the artwork lives in net/solace/api/ui/ - the single copy.
        var on = ImageUtil.loadImageResource(Switcher.class, "switcher_on.png");
        var off = ImageUtil.loadImageResource(Switcher.class, "switcher_off.png");

        ON = new ImageIcon(ImageUtil.recolorImage(on, ColorScheme.BRAND_CRIMSON));
        OFF = new ImageIcon(off);
        DISABLED = new ImageIcon(ImageUtil.alphaOffset(off, -100));
    }
}

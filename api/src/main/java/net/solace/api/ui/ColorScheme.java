package net.solace.api.ui;

import java.awt.Color;

/**
 * Solace's own palette.
 *
 * <p>Names are deliberately <b>disjoint</b> from {@code net.runelite.client.ui.ColorScheme}'s
 * ({@code DARK_GRAY_COLOR}, {@code BORDER_COLOR}, {@code TEXT_COLOR}, …). Several files import both
 * classes, so a shared name would let {@code ColorScheme.X} compile under either import and silently
 * resolve to the wrong grey. With disjoint names that mistake is always a compile error.
 *
 * <p>Convention: a bare {@code ColorScheme} reference means RuneLite's — it supplies every panel
 * background. Reach for these via static import of the specific constant.
 */
public class ColorScheme {
    /** Crimson — primary Solace accent (#EF4444). */
    public static final Color BRAND_CRIMSON = new Color(0xEF4444);
    /** Lighter crimson for hover / active states (#F87171). */
    public static final Color BRAND_CRIMSON_HOVER = new Color(0xF87171);
    /** Crimson at ~40% opacity for overlays. */
    public static final Color BRAND_CRIMSON_TRANSPARENT = new Color(239, 68, 68, 102);

    /** Background of an inset control - text fields, spinners, combo boxes, log panes. */
    public static final Color SURFACE = new Color(0x232323);
    /** {@link #SURFACE} under the cursor. */
    public static final Color SURFACE_HOVER = new Color(0x2E2E2E);
    /** Hairline around an inset control, and dividers. */
    public static final Color BORDER = new Color(0x3C3C3C);
    /** Body text on a dark background. */
    public static final Color TEXT_PRIMARY = new Color(0xDCDCDC);
    /** De-emphasised text and glyphs - spinner chevrons, secondary labels. */
    public static final Color TEXT_SECONDARY = new Color(0x9A9A9A);

    /** OSRS in-game &lt;col&gt; tag hex (no # prefix). */
    public static final String BRAND_HEX = "EF4444";

    public static String brandCol(String text) {
        return "<col=" + BRAND_HEX + ">" + text + "</col>";
    }
}

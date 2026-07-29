package net.solace.api.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.Container;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;

/**
 * Shared look for the config panel's editable controls (text fields, spinners, combo boxes) so they
 * line up with the switcher toggles: same row height, same corner radius, same idle/focus colours.
 */
public final class InputStyle {
    /** Height of a single-line input; matches the switcher icon's row. */
    public static final int FIELD_HEIGHT = 24;
    public static final int RADIUS = 6;

    // Field-specific aliases for the shared palette. The values live in ColorScheme so the frames and
    // dialogs outside this class can reach the same greys; these names stay because they read better
    // at the call sites here.
    public static final Color FIELD_BACKGROUND = ColorScheme.SURFACE;
    public static final Color FIELD_BACKGROUND_HOVER = ColorScheme.SURFACE_HOVER;
    public static final Color FIELD_BORDER = ColorScheme.BORDER;
    public static final Color FIELD_BORDER_FOCUS = ColorScheme.BRAND_CRIMSON;
    public static final Color FIELD_FOREGROUND = ColorScheme.TEXT_PRIMARY;
    public static final Color ARROW = ColorScheme.TEXT_SECONDARY;

    private InputStyle() {
    }

    /** A single-line text field / text area. Pass the panel colour so the rounded corners blend in. */
    public static <T extends JTextComponent> T style(T field, Color parentBackground) {
        field.setBackground(FIELD_BACKGROUND);
        field.setForeground(FIELD_FOREGROUND);
        field.setCaretColor(ColorScheme.BRAND_CRIMSON);
        field.setSelectionColor(ColorScheme.BRAND_CRIMSON_TRANSPARENT);
        field.setSelectedTextColor(Color.WHITE);
        field.setBorder(new RoundedBorder(parentBackground, 6));
        repaintOnFocus(field);
        return field;
    }

    /** Flattens a spinner: rounded field, stacked chevron buttons, no LAF chrome. */
    public static JSpinner style(JSpinner spinner, Color parentBackground) {
        final var editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            final JTextField text = ((JSpinner.DefaultEditor) editor).getTextField();
            text.setBackground(FIELD_BACKGROUND);
            text.setForeground(FIELD_FOREGROUND);
            text.setCaretColor(ColorScheme.BRAND_CRIMSON);
            text.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
            text.setHorizontalAlignment(JTextField.RIGHT);
            ((JComponent) editor).setBorder(BorderFactory.createEmptyBorder());
            ((JComponent) editor).setBackground(FIELD_BACKGROUND);
        }

        spinner.setBackground(FIELD_BACKGROUND);
        spinner.setBorder(new RoundedBorder(parentBackground, 0));

        for (var child : spinner.getComponents()) {
            if (child instanceof AbstractButton) {
                final var button = (AbstractButton) child;
                final boolean up = spinner.getComponentZOrder(button) == 0;
                button.setBorder(BorderFactory.createEmptyBorder());
                button.setBackground(FIELD_BACKGROUND);
                button.setFocusable(false);
                button.setContentAreaFilled(false);
                button.setPreferredSize(new Dimension(14, FIELD_HEIGHT / 2));
                button.setIcon(new ChevronIcon(up ? -1 : 1));
            }
        }

        fixHeight(spinner);
        repaintOnFocus(spinner);
        return spinner;
    }

    /** Flattens a combo box: rounded field, single chevron, dark popup with a crimson selection. */
    public static <T> JComboBox<T> style(JComboBox<T> box, Color parentBackground) {
        box.setUI(new FlatComboBoxUI());
        box.setBackground(FIELD_BACKGROUND);
        box.setForeground(FIELD_FOREGROUND);
        box.setBorder(new RoundedBorder(parentBackground, 0));
        box.setFocusable(false);
        fixHeight(box);
        return box;
    }

    /**
     * Buttons -- action buttons and hotkey capture alike -- get the same rounded field as the inputs,
     * lightening on hover so they still read as clickable.
     */
    public static <T extends AbstractButton> T style(T button, Color parentBackground) {
        styleFreeHeight(button, parentBackground);
        fixHeight(button);
        return button;
    }

    /**
     * The button look without the height pin, for buttons whose row height the caller owns - a
     * full-width list row, or anything already given an explicit preferred size. {@link
     * #style(AbstractButton, Color)} forces 24px, which silently collapses those.
     */
    public static <T extends AbstractButton> T styleFreeHeight(T button, Color parentBackground) {
        button.setUI(new BasicButtonUI());
        button.setOpaque(true);
        button.setBackground(FIELD_BACKGROUND);
        button.setForeground(FIELD_FOREGROUND);
        button.setBorder(new RoundedBorder(parentBackground, 6));
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(FIELD_BACKGROUND_HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(FIELD_BACKGROUND);
            }
        });

        repaintOnFocus(button);
        return button;
    }

    /**
     * Applies the Solace look to a whole component tree, for surfaces built from raw Swing that would
     * otherwise render as default Metal.
     *
     * <p>Buttons go through {@link #styleFreeHeight} rather than {@link #style}, deliberately: a tree
     * walk cannot know which buttons own their row height, and pinning every one to 24px would
     * silently collapse list rows and fixed-size button grids.
     */
    public static void themeTree(Component root, Color background) {
        if (root instanceof JTextComponent) {
            style((JTextComponent) root, background);
        } else if (root instanceof JComboBox) {
            style((JComboBox<?>) root, background);
        } else if (root instanceof JSpinner) {
            style((JSpinner) root, background);
        } else if (root instanceof AbstractButton) {
            styleFreeHeight((AbstractButton) root, background);
        } else if (root instanceof JLabel) {
            root.setForeground(ColorScheme.TEXT_PRIMARY);
        } else if (root instanceof JPanel || root instanceof JScrollPane || root instanceof JViewport) {
            root.setBackground(background);
        }

        if (root instanceof Container) {
            for (var child : ((Container) root).getComponents()) {
                themeTree(child, background);
            }
        }
    }

    private static void fixHeight(JComponent component) {
        final var preferred = component.getPreferredSize();
        component.setPreferredSize(new Dimension(preferred.width, FIELD_HEIGHT));
        component.setMinimumSize(new Dimension(0, FIELD_HEIGHT));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_HEIGHT));
    }

    private static void repaintOnFocus(JComponent component) {
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                component.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                component.repaint();
            }
        });
    }

    private static boolean hasFocus(Component c) {
        if (c.isFocusOwner()) {
            return true;
        }
        if (c instanceof JComponent) {
            for (var child : ((JComponent) c).getComponents()) {
                if (child.isFocusOwner()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Rounded outline that paints the panel colour back into the corners. */
    public static final class RoundedBorder implements Border {
        private final Color parentBackground;
        private final int horizontalPadding;

        public RoundedBorder(Color parentBackground, int horizontalPadding) {
            this.parentBackground = parentBackground;
            this.horizontalPadding = horizontalPadding;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            final var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Knock only the square corners the component just painted back to the panel colour --
            // filling the interior here would paint over the component's own content.
            final var corners = new Area(new Rectangle(x, y, width, height));
            corners.subtract(new Area(new RoundRectangle2D.Float(x, y, width - 1, height - 1, RADIUS, RADIUS)));
            g2.setColor(parentBackground);
            g2.fill(corners);

            g2.setStroke(new BasicStroke(1f));
            g2.setColor(hasFocus(c) ? FIELD_BORDER_FOCUS : FIELD_BORDER);
            g2.drawRoundRect(x, y, width - 1, height - 1, RADIUS, RADIUS);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(3, horizontalPadding + 1, 3, horizontalPadding + 1);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /** Small chevron used by both the spinner buttons and the combo box arrow. */
    private static final class ChevronIcon implements javax.swing.Icon {
        private final int direction; // -1 up, 1 down

        private ChevronIcon(int direction) {
            this.direction = direction;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            final var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(c.isEnabled() ? ARROW : ARROW.darker());

            final int w = getIconWidth();
            final int h = getIconHeight();
            final int midX = x + w / 2;
            final int topY = direction < 0 ? y + h - 2 : y + 2;
            final int tipY = direction < 0 ? y + 2 : y + h - 2;
            g2.drawLine(x + 1, topY, midX, tipY);
            g2.drawLine(midX, tipY, x + w - 1, topY);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 9;
        }

        @Override
        public int getIconHeight() {
            return 7;
        }
    }

    /** Combo box UI with no LAF chrome: flat arrow button and a dark, borderless popup. */
    private static final class FlatComboBoxUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            final var button = new JButton(new ChevronIcon(1));
            button.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
            button.setContentAreaFilled(false);
            button.setFocusable(false);
            return button;
        }

        @Override
        protected ComboPopup createPopup() {
            final var popup = new BasicComboPopup(comboBox) {
                @Override
                protected void configureList() {
                    super.configureList();
                    list.setBackground(FIELD_BACKGROUND);
                    list.setForeground(FIELD_FOREGROUND);
                    list.setSelectionBackground(ColorScheme.BRAND_CRIMSON);
                    list.setSelectionForeground(Color.WHITE);
                }
            };
            popup.setBorder(BorderFactory.createLineBorder(FIELD_BORDER));
            return popup;
        }

        @Override
        public void configureEditor() {
            super.configureEditor();
            if (editor instanceof JComponent) {
                ((JComponent) editor).setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            }
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            padding = new Insets(0, 6, 0, 0);
        }
    }
}

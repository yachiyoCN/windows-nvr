package cn.homewatch;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;

/** Small, predictable controls whose colors do not depend on Windows theme painting. */
public final class NvrUi {
    public static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    private static final Color TEXT = new Color(59, 64, 70);
    private static final Color RED = new Color(202, 43, 47);
    private static final Color BORDER = new Color(204, 209, 215);
    private static final String PRIMARY = "homewatch.primary";

    private NvrUi() {}

    public static JButton button(String text, Icon icon, boolean primary, Runnable action) {
        JButton button = new JButton(text, icon);
        styleButton(button, primary);
        if (action != null) button.addActionListener(event -> action.run());
        return button;
    }

    public static void styleButton(JButton button, boolean primary) {
        button.putClientProperty(PRIMARY, primary);
        button.setUI(new FlatButtonUI());
        button.setFont(FONT);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(true);
        button.setRolloverEnabled(true);
        button.setBorder(BorderFactory.createEmptyBorder(6, 11, 6, 11));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setIconTextGap(6);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // Clear old fixed sizes: translated labels and changing actions must stay readable.
        button.setPreferredSize(null);
        button.setMinimumSize(null);
        button.setMaximumSize(null);
    }

    public static void styleCombo(JComboBox<?> combo) {
        combo.setFont(FONT);
        combo.setForeground(TEXT);
        combo.setBackground(Color.WHITE);
        Dimension size = combo.getPreferredSize();
        combo.setPreferredSize(new Dimension(size.width, Math.max(32, size.height)));
        combo.setMinimumSize(new Dimension(70, 32));
    }

    public static void styleSpinner(JSpinner spinner) {
        spinner.setFont(FONT);
        spinner.setBorder(BorderFactory.createLineBorder(BORDER));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setFont(FONT);
            editor.getTextField().setForeground(TEXT);
            editor.getTextField().setMargin(new Insets(3, 5, 3, 5));
        }
        Dimension size = spinner.getPreferredSize();
        spinner.setPreferredSize(new Dimension(size.width, Math.max(32, size.height)));
        spinner.setMinimumSize(new Dimension(65, 32));
    }

    public static void styleField(JTextField field) {
        field.setFont(FONT);
        field.setForeground(TEXT);
        field.setBackground(Color.WHITE);
        field.setCaretColor(TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(5, 6, 5, 6)));
        Dimension size = field.getPreferredSize();
        field.setPreferredSize(new Dimension(size.width, Math.max(32, size.height)));
        field.setMinimumSize(new Dimension(60, 32));
    }

    private static final class FlatButtonUI extends BasicButtonUI {
        @Override public Dimension getPreferredSize(JComponent component) {
            Dimension result = super.getPreferredSize(component);
            if (result == null) result = new Dimension(32, 32);
            return new Dimension(Math.max(32, result.width), Math.max(32, result.height));
        }

        @Override public Dimension getMinimumSize(JComponent component) {
            return getPreferredSize(component);
        }

        @Override public void update(Graphics graphics, JComponent component) {
            paint(graphics, component);
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            JButton button = (JButton) component;
            ButtonModel model = button.getModel();
            boolean primary = Boolean.TRUE.equals(button.getClientProperty(PRIMARY));
            Color fill, line;
            if (!button.isEnabled()) {
                fill = new Color(241, 243, 245);
                line = new Color(220, 224, 228);
            } else if (primary) {
                fill = model.isPressed() && model.isArmed() ? new Color(165, 29, 33)
                        : model.isRollover() ? new Color(181, 34, 39) : RED;
                line = fill;
            } else if (model.isSelected()) {
                fill = model.isPressed() && model.isArmed() ? new Color(249, 216, 216)
                        : new Color(255, 240, 240);
                line = RED;
            } else {
                fill = model.isPressed() && model.isArmed() ? new Color(224, 229, 233)
                        : model.isRollover() ? new Color(242, 245, 247) : Color.WHITE;
                line = model.isRollover() ? new Color(153, 162, 173) : BORDER;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 5, 5);
            g.setColor(line);
            g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 5, 5);
            if (button.isFocusOwner() && button.isEnabled()) {
                g.setColor(primary ? Color.WHITE : RED);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                        1f, new float[] {2, 2}, 0));
                g.drawRoundRect(3, 3, component.getWidth() - 7, component.getHeight() - 7, 3, 3);
            }
            g.dispose();
            super.paint(graphics, component);
        }

        @Override protected void paintText(Graphics graphics, JComponent component,
                                          Rectangle rectangle, String text) {
            JButton button = (JButton) component;
            boolean primary = Boolean.TRUE.equals(button.getClientProperty(PRIMARY));
            graphics.setColor(!button.isEnabled() ? new Color(144, 151, 160)
                    : primary ? Color.WHITE : button.getModel().isSelected() ? RED : button.getForeground());
            graphics.setFont(button.getFont());
            BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, text,
                    button.getDisplayedMnemonicIndex(), rectangle.x + getTextShiftOffset(),
                    rectangle.y + graphics.getFontMetrics().getAscent() + getTextShiftOffset());
        }
    }
}

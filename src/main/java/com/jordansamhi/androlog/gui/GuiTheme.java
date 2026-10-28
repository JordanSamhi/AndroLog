package com.jordansamhi.androlog.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;

/** Small, dependency-free desktop theme; all controls keep Swing's keyboard and accessibility support. */
final class GuiTheme {
    static final Color INK = new Color(0x182D39), MUTED = new Color(0x647681), ACCENT = new Color(0x007F78);
    static final Color BACKGROUND = new Color(0xF6F8FA), LINE = new Color(0xDFE7EB), TINT = new Color(0xEAF7F4);
    static final Color SIDEBAR = new Color(0x122C36), WHITE = Color.WHITE;
    static final String FONT = System.getProperty("os.name").startsWith("Mac") ? "Helvetica Neue" : "SansSerif";

    static void install() {
        // A consistent base avoids platform gradients while retaining native window chrome and file dialogs.
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) { /* The JRE's default look and feel is a usable fallback. */ }
        UIManager.put("Label.font", font(14, false));
        UIManager.put("Button.font", font(14, true));
        UIManager.put("CheckBox.font", font(13, false));
        UIManager.put("TextField.font", font(14, false));
        UIManager.put("OptionPane.messageFont", font(14, false));
        UIManager.put("OptionPane.buttonFont", font(13, true));
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("TextField.selectionBackground", new Color(0xB4E5DC));
        UIManager.put("ScrollBar.width", 10);
    }

    static Font font(int size, boolean bold) { return new Font(FONT, bold ? Font.BOLD : Font.PLAIN, size); }
    static JPanel panel(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel label = new JLabel(text); label.setFont(font(size, bold)); label.setForeground(color); return label;
    }
    static JLabel muted(String text) { return label(text, 13, MUTED, false); }
    static String html(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static JButton button(String text, boolean primary) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = graphics(graphics);
                Color fill = primary ? ACCENT : WHITE;
                if (!isEnabled()) fill = primary ? new Color(0xA7C6C3) : BACKGROUND;
                else if (getModel().isPressed()) fill = primary ? ACCENT.darker() : LINE;
                else if (getModel().isRollover()) fill = primary ? new Color(0x006F69) : TINT;
                g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g.setColor(isFocusOwner() ? ACCENT : (primary ? fill : LINE));
                g.setStroke(new BasicStroke(isFocusOwner() ? 2 : 1));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setUI(new BasicButtonUI());
        button.setOpaque(false); button.setContentAreaFilled(false); button.setBorderPainted(false); button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(13, 20, 13, 20));
        button.setFont(font(14, true)); button.setForeground(primary ? WHITE : INK);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    static JTextField field(String value, int columns) {
        JTextField field = new JTextField(value, columns);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new EmptyBorder(9, 10, 9, 10)));
        field.setForeground(INK); return field;
    }

    static Graphics2D graphics(Graphics original) {
        Graphics2D g = (Graphics2D) original.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static class Card extends JPanel {
        Color fill = WHITE;
        Color stroke = LINE;
        Card(LayoutManager layout) { super(layout); setOpaque(false); setBorder(new EmptyBorder(22, 24, 22, 24)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            g.setColor(new Color(20, 50, 60, 5)); g.fillRoundRect(0, 3, getWidth(), getHeight() - 3, 24, 24);
            g.setColor(fill); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 4, 24, 24);
            g.setColor(stroke); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 4, 24, 24);
            g.dispose(); super.paintComponent(graphics);
        }
    }

    static JToggleButton probe(String title, String description, String symbol, boolean selected) {
        JToggleButton button = new JToggleButton("<html><b>" + title + "</b><br><font color='#647681' size='3'>" + description + "</font></html>", selected) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = graphics(graphics);
                g.setColor(isSelected() ? TINT : WHITE); g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 20, 20);
                g.setColor(isSelected() || isFocusOwner() ? ACCENT : LINE);
                g.setStroke(new BasicStroke(isFocusOwner() ? 2.5f : isSelected() ? 1.5f : 1));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 20, 20);
                int x = getWidth() - 30, y = 22;
                if (isSelected()) {
                    g.fillOval(x, y, 16, 16); g.setColor(WHITE); g.setStroke(new BasicStroke(1.7f));
                    g.drawLine(x + 4, y + 8, x + 7, y + 11); g.drawLine(x + 7, y + 11, x + 12, y + 5);
                } else g.drawOval(x, y, 16, 16);
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setUI(new BasicButtonUI());
        button.setOpaque(false); button.setContentAreaFilled(false); button.setBorderPainted(false); button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(17, 18, 17, 40));
        button.setHorizontalAlignment(SwingConstants.LEFT); button.setIconTextGap(13);
        button.setIcon(new Icon() {
            public int getIconWidth() { return 32; }
            public int getIconHeight() { return 36; }
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = graphics(graphics);
                g.setColor(ACCENT); g.setFont(font(19, true)); g.drawString(symbol, x, y + 24); g.dispose();
            }
        });
        button.setFont(font(15, false)); button.setForeground(INK);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.getAccessibleContext().setAccessibleName(title);
        button.getAccessibleContext().setAccessibleDescription(description);
        return button;
    }
}

package com.jordansamhi.androlog.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;

/** Glass surfaces rendered in Swing, retaining native keyboard and accessibility behavior. */
final class GuiTheme {
    static final Color INK = new Color(0x183441), MUTED = new Color(0x536E7D), ACCENT = new Color(0x007E79);
    static final Color BACKGROUND = new Color(0xEDF4F8), LINE = new Color(0xCCDCE3), TINT = new Color(0xDFF5EF);
    static final Color WHITE = Color.WHITE;
    static final String FONT = System.getProperty("os.name").startsWith("Mac") ? "Helvetica Neue" : "SansSerif";
    static boolean animationsEnabled = true;

    static void install() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) { /* The default look and feel remains usable. */ }
        UIManager.put("Label.font", font(14, false));
        UIManager.put("Button.font", font(14, true));
        UIManager.put("CheckBox.font", font(13, false));
        UIManager.put("CheckBox.icon", new Icon() {
            public int getIconWidth() { return 23; }
            public int getIconHeight() { return 23; }
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                AbstractButton button = (AbstractButton) component;
                Graphics2D g = graphics(graphics); g.translate(x + 2, y + 2);
                g.setColor(button.isSelected() ? ACCENT : new Color(255, 255, 255, 210));
                g.fillRoundRect(1, 1, 17, 17, 8, 8);
                g.setColor(button.isFocusOwner() ? ACCENT : button.isSelected() ? ACCENT : LINE.darker());
                g.setStroke(new BasicStroke(button.isFocusOwner() ? 2 : 1)); g.drawRoundRect(1, 1, 17, 17, 8, 8);
                if (button.isSelected()) {
                    g.setColor(WHITE); g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(5, 9, 8, 12); g.drawLine(8, 12, 14, 6);
                }
                g.dispose();
            }
        });
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

    static JButton button(String text, boolean primary) { return new GlassButton(text, primary ? 2 : 1); }
    static JButton textButton(String text) { return new GlassButton(text, 0); }

    private static final class GlassButton extends JButton {
        private final int kind;
        private final Hover hover;
        GlassButton(String text, int kind) {
            super(text); this.kind = kind;
            setUI(new BasicButtonUI()); setOpaque(false); setContentAreaFilled(false);
            setBorderPainted(false); setFocusPainted(false); setRolloverEnabled(true);
            setBorder(new EmptyBorder(kind == 0 ? 9 : 14, kind == 0 ? 10 : 22, kind == 0 ? 9 : 14, kind == 0 ? 10 : 22));
            setFont(font(kind == 0 ? 12 : 14, kind != 0));
            setForeground(kind == 2 ? WHITE : kind == 0 ? MUTED : INK);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            hover = new Hover(this);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            float h = hover.value;
            int y = getModel().isPressed() && isEnabled() ? 4 : 3;
            int width = getWidth() - 7, height = getHeight() - 8;
            Shape shape = new RoundRectangle2D.Float(3, y, width, height, height, height);
            if (kind != 0) {
                g.setColor(new Color(24, 65, 77, isEnabled() ? 12 + (int) (h * 8) : 4));
                g.fill(new RoundRectangle2D.Float(3, y + 3, width, height, height, height));
                Color top = kind == 2 ? new Color(0x006C68) : new Color(255, 255, 255, 235);
                Color bottom = kind == 2 ? new Color(0x005A65) : new Color(230, 243, 249, 185);
                if (!isEnabled()) { top = new Color(0xD5E3E7); bottom = new Color(0xCDDBE0); }
                else if (h > 0) {
                    top = blend(top, kind == 2 ? new Color(0x007A70) : WHITE, h * .65f);
                    bottom = blend(bottom, kind == 2 ? new Color(0x00606C) : new Color(0xD6F1EE), h * .45f);
                }
                g.setPaint(new GradientPaint(0, y, top, 0, y + height, bottom)); g.fill(shape);
                g.setColor(kind == 2 ? new Color(255, 255, 255, 105) : new Color(255, 255, 255, 245));
                g.setStroke(new BasicStroke(1.2f)); g.draw(shape);
                Shape clip = g.getClip(); g.clip(shape);
                g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, kind == 2 ? 20 : 140), 0, y + height / 2f, new Color(255, 255, 255, 0)));
                g.fillRect(3, y, width, height / 2); g.setClip(clip);
            } else if (h > 0) {
                g.setColor(new Color(255, 255, 255, (int) (h * 160))); g.fill(shape);
            }
            if (isFocusOwner()) {
                g.setColor(new Color(0, 126, 121, 155)); g.setStroke(new BasicStroke(2));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, getHeight(), getHeight());
            }
            g.dispose(); super.paintComponent(graphics);
        }
    }

    /** One short, event-driven animation; no timer runs while a control is idle or hidden. */
    private static final class Hover {
        float value;
        private final AbstractButton button;
        private final Timer timer;
        Hover(AbstractButton button) {
            this.button = button;
            timer = new Timer(16, event -> tick());
            button.getModel().addChangeListener(event -> update());
            button.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { button.repaint(); }
                @Override public void focusLost(FocusEvent event) { button.repaint(); }
            });
            button.addHierarchyListener(event -> { if (!button.isShowing()) timer.stop(); });
        }
        private float target() { return button.isEnabled() && (button.getModel().isRollover() || button.getModel().isPressed()) ? 1 : 0; }
        private void update() {
            if (!animationsEnabled || !button.isShowing()) { timer.stop(); value = target(); button.repaint(); }
            else if (!timer.isRunning()) timer.start();
        }
        private void tick() {
            float target = target(); value += (target - value) * .24f;
            if (!animationsEnabled || Math.abs(target - value) < .01f) { value = target; timer.stop(); }
            button.repaint();
        }
    }

    static JTextField field(String value, int columns) {
        JTextField field = new JTextField(value, columns) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = graphics(graphics);
                g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 235), 0, getHeight(), new Color(255, 255, 255, 155)));
                g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
                g.setColor(isFocusOwner() ? ACCENT : LINE); g.setStroke(new BasicStroke(isFocusOwner() ? 1.8f : 1));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18); g.dispose(); super.paintComponent(graphics);
            }
        };
        field.setOpaque(false); field.setBorder(new EmptyBorder(11, 13, 11, 13)); field.setForeground(INK); field.setCaretColor(ACCENT);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { field.repaint(); }
            @Override public void focusLost(FocusEvent event) { field.repaint(); }
        });
        return field;
    }

    static Graphics2D graphics(Graphics original) {
        Graphics2D g = (Graphics2D) original.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static Color blend(Color a, Color b, float amount) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * amount),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * amount),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * amount));
    }

    static final class Background extends JPanel {
        Background(LayoutManager layout) { super(layout); setBackground(BACKGROUND); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); Graphics2D g = graphics(graphics);
            g.setPaint(new GradientPaint(0, 0, new Color(0xF1F7FA), getWidth(), getHeight(), new Color(0xE9F0F8)));
            g.fillRect(0, 0, getWidth(), getHeight());
            glow(g, getWidth() * .05f, getHeight() * .28f, Math.max(260, getWidth() * .48f), new Color(112, 219, 199, 86));
            glow(g, getWidth() * .92f, getHeight() * .1f, Math.max(260, getWidth() * .52f), new Color(145, 201, 247, 80));
            glow(g, getWidth() * .7f, getHeight() * .98f, Math.max(250, getWidth() * .42f), new Color(192, 174, 234, 63));
            g.dispose();
        }
        private void glow(Graphics2D g, float x, float y, float radius, Color color) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), radius, new float[]{0, 1},
                    new Color[]{color, new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    static class Card extends JPanel {
        Color fill = new Color(255, 255, 255, 165);
        Color stroke = new Color(255, 255, 255, 240);
        Card(LayoutManager layout) { super(layout); setOpaque(false); setBorder(new EmptyBorder(22, 24, 22, 24)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            for (int i = 3; i > 0; i--) {
                g.setColor(new Color(25, 65, 83, 3)); g.fillRoundRect(i, 3 + i, getWidth() - i * 2, getHeight() - 6, 30, 30);
            }
            g.setPaint(new GradientPaint(0, 0, blend(fill, WHITE, .45f), getWidth(), getHeight(), fill));
            g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 6, 28, 28);
            g.setColor(stroke); g.setStroke(new BasicStroke(1.2f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 6, 28, 28);
            g.dispose(); super.paintComponent(graphics);
        }
    }

    static JLabel step(String text) {
        JLabel label = new JLabel(text) {
            @Override protected void paintComponent(Graphics graphics) {
                if (Boolean.TRUE.equals(getClientProperty("active"))) {
                    Graphics2D g = graphics(graphics);
                    g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 245), 0, getHeight(), new Color(217, 244, 239, 190)));
                    g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 20, 20);
                    g.setColor(WHITE); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 20, 20); g.dispose();
                }
                super.paintComponent(graphics);
            }
        };
        label.setBorder(new EmptyBorder(14, 12, 14, 9)); label.setFont(font(13, false)); label.setForeground(MUTED); return label;
    }

    static JToggleButton probe(String title, String description, String symbol, boolean selected) {
        JToggleButton button = new JToggleButton("<html><b>" + title + "</b><br><font color='#536E7D' size='3'>" + description + "</font></html>", selected) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = graphics(graphics);
                Color top = isSelected() ? new Color(0xEDFCF7) : new Color(255, 255, 255, 225);
                Color bottom = isSelected() ? new Color(208, 240, 232, 210) : new Color(245, 249, 255, 145);
                g.setPaint(new GradientPaint(0, 0, top, getWidth(), getHeight(), bottom));
                g.fillRoundRect(2, 2, getWidth() - 5, getHeight() - 6, 24, 24);
                g.setColor(isSelected() || isFocusOwner() ? new Color(0, 126, 121, 160) : WHITE);
                g.setStroke(new BasicStroke(isFocusOwner() ? 2.5f : 1.2f));
                g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 6, 24, 24);
                if (getModel().isRollover()) {
                    g.setColor(new Color(255, 255, 255, 45)); g.fillRoundRect(3, 3, getWidth() - 7, getHeight() - 8, 24, 24);
                }
                int x = getWidth() - 29, y = 19;
                g.setColor(isSelected() ? ACCENT : LINE.darker());
                if (isSelected()) {
                    g.fillOval(x, y, 17, 17); g.setColor(WHITE); g.setStroke(new BasicStroke(1.8f));
                    g.drawLine(x + 4, y + 8, x + 7, y + 11); g.drawLine(x + 7, y + 11, x + 12, y + 5);
                } else g.drawOval(x, y, 16, 16);
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setUI(new BasicButtonUI()); button.setRolloverEnabled(true);
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
        button.getAccessibleContext().setAccessibleName(title); button.getAccessibleContext().setAccessibleDescription(description);
        return button;
    }
}

package com.jordansamhi.androlog.gui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/** A clickable coverage statistic. A null fraction means there is no comparable baseline. */
final class CoverageCard extends JButton {
    private final String title;
    private final Color accent;
    private String value = "—", detail = "Open a runtime log";
    private Double fraction;

    CoverageCard(String title, Color accent) {
        this.title = title; this.accent = accent; setUI(new BasicButtonUI());
        setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); setPreferredSize(new Dimension(150, 116));
        getAccessibleContext().setAccessibleName(title + ": no log loaded");
    }
    void showObservation(long count, boolean comparedOtherCategories) {
        value = String.format(Locale.ROOT, "%,d", count);
        detail = comparedOtherCategories ? "Observed · not compared" : "Observed · compare APK";
        fraction = null; updateAccessible();
    }
    void showCoverage(CoverageJob.Metric metric) {
        if (metric.total == 0) { value = "—"; detail = "Nothing to cover"; fraction = null; }
        else {
            value = metric.display(); detail = String.format(Locale.ROOT, "%,d of %,d observed", metric.observed, metric.total);
            fraction = Math.min(1, Math.max(0, (double) metric.observed / metric.total));
        }
        updateAccessible();
    }
    private void updateAccessible() { getAccessibleContext().setAccessibleName(title + ": " + value + ", " + detail); repaint(); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        Shape card = new RoundRectangle2D.Float(2, 2, Math.max(0, w - 5), Math.max(0, h - 5), 18, 18);
        g.setColor(new Color(255, 255, 255, getModel().isRollover() ? 245 : 205)); g.fill(card);
        g.setColor(isFocusOwner() ? accent : new Color(255, 255, 255, 245));
        g.setStroke(new BasicStroke(isFocusOwner() ? 2f : 1f)); g.draw(card);
        int x = 15; g.setColor(GuiTheme.MUTED); g.setFont(GuiTheme.font(13, true));
        g.drawString(title, x, 23);
        g.setColor(GuiTheme.INK); g.setFont(GuiTheme.font(26, true)); g.drawString(value, x, 57);
        g.setColor(GuiTheme.MUTED); g.setFont(GuiTheme.font(11, false)); g.drawString(detail, x, 76);
        int barWidth = Math.max(0, w - 31), y = h - 17;
        g.setColor(new Color(0xD8E3E8)); g.fillRoundRect(x, y, barWidth, 5, 5, 5);
        if (fraction != null && fraction > 0) { g.setColor(accent); g.fillRoundRect(x, y, Math.max(3, (int) Math.round(barWidth * fraction)), 5, 5, 5); }
        g.dispose();
    }
}

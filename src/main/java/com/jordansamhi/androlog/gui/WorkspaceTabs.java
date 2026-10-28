package com.jordansamhi.androlog.gui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;

/** Rounded workspace tabs with Swing's standard tab navigation and accessibility. */
final class WorkspaceTabs extends BasicTabbedPaneUI {
    @Override protected void installDefaults() {
        super.installDefaults();
        tabInsets = new Insets(10, 20, 10, 20);
        selectedTabPadInsets = new Insets(0, 0, 0, 0);
        tabAreaInsets = new Insets(0, 0, 10, 0);
        contentBorderInsets = new Insets(0, 0, 0, 0);
    }
    @Override protected void paintTabBackground(Graphics graphics, int placement, int index,
                                                int x, int y, int width, int height, boolean selected) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(selected ? new Color(255, 255, 255, 220) : new Color(255, 255, 255, 85));
        g.fillRoundRect(x + 2, y + 2, width - 4, height - 4, 20, 20);
        if (selected) { g.setColor(new Color(255, 255, 255, 240)); g.drawRoundRect(x + 2, y + 2, width - 5, height - 5, 20, 20); }
        g.dispose();
    }
    @Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) { }
    @Override protected void paintContentBorder(Graphics g, int placement, int selectedIndex) { }
    @Override protected void paintFocusIndicator(Graphics graphics, int placement, Rectangle[] rects,
                                                   int index, Rectangle iconRect, Rectangle textRect, boolean selected) {
        if (!tabPane.hasFocus() || !selected) return;
        Rectangle r = rects[index]; Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(GuiTheme.ACCENT); g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(r.x + 3, r.y + 3, r.width - 7, r.height - 7, 18, 18); g.dispose();
    }
}

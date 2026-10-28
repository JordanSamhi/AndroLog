package com.jordansamhi.androlog.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import static com.jordansamhi.androlog.gui.GuiTheme.*;

/** A floating bytecode stack: probes light up between its layers as the real worker advances. */
final class InstrumentationAnimation extends JPanel {
    private final Timer timer;
    private final long born = System.nanoTime();
    private boolean animate = true;
    private int stage;

    InstrumentationAnimation() {
        setOpaque(false); setPreferredSize(new Dimension(580, 250));
        getAccessibleContext().setAccessibleName("APK instrumentation illustration");
        timer = new Timer(33, event -> repaint());
    }

    void setStage(int value) { stage = value; repaint(); }
    void setAnimationEnabled(boolean value) { animate = value; updateTimer(); repaint(); }
    @Override public void addNotify() { super.addNotify(); updateTimer(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    private void updateTimer() { if (animate && isShowing()) timer.start(); else timer.stop(); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = graphics(graphics);
        double time = animate ? (System.nanoTime() - born) / 1_000_000_000.0 : 0;
        double cx = getWidth() / 2.0, cy = getHeight() / 2.0 + 15;
        double floating = animate && stage < 5 ? Math.sin(time * 1.5) * 5 : 0;
        g.translate(cx, cy + floating);
        double scale = Math.min(1.0, Math.min(getWidth() / 580.0, getHeight() / 250.0));
        g.scale(scale, scale);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 155, new float[]{0, 1},
                new Color[]{new Color(127, 220, 207, 65), new Color(127, 220, 207, 0)}));
        g.fill(new Ellipse2D.Double(-170, -126, 340, 245));
        g.setColor(new Color(0xD6E6E6)); g.setStroke(new BasicStroke(1));
        g.draw(new Line2D.Double(-250, 6, -112, 6)); g.draw(new Line2D.Double(112, 6, 250, 6));
        for (int i = 0; i < 6; i++) {
            double phase = (time * 0.22 + i / 6.0) % 1;
            double x = -245 + phase * 490;
            if (Math.abs(x) > 115 && stage > 0 && stage < 5) {
                g.setColor(new Color(0x55BDAA)); g.fill(new RoundRectangle2D.Double(x, 2, 8, 8, 3, 3));
            }
        }
        for (int layer = 3; layer >= 0; layer--) {
            double y = (layer - 1.5) * 23;
            Path2D shape = new Path2D.Double();
            shape.moveTo(0, y - 48); shape.lineTo(111, y); shape.lineTo(0, y + 48); shape.lineTo(-111, y); shape.closePath();
            g.setPaint(new GradientPaint(-100, (float) y - 48, new Color(255, 255, 255, 245),
                    100, (float) y + 48, layer == 0 ? new Color(217, 244, 245, 220) : new Color(199, 232, 235, 200)));
            g.fill(shape);
            g.setColor(layer == 0 ? new Color(0x79BFB8) : new Color(0x9ABEC7)); g.draw(shape);
            for (int row = 0; row < 5; row++) {
                double x = -52 + row * 22;
                double yLine = y + 18 + x * 0.43;
                g.setColor(stage >= 2 && (row + layer) % 3 != 0 ? ACCENT : new Color(0xBCD5D0));
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(x, yLine, x + 21, yLine - 9));
            }
            g.setStroke(new BasicStroke(1));
        }
        g.setFont(font(15, true)); g.setColor(INK); g.drawString("APK", -16, -36);
        if (stage >= 2 && stage < 5) {
            double sweep = animate ? (time * 0.34) % 1 : 0.5;
            g.setColor(new Color(0, 127, 120, 100));
            g.setStroke(new BasicStroke(2));
            g.draw(new Line2D.Double(-90 + sweep * 110, -45 + sweep * 48, sweep * 110, -84 + sweep * 48));
        }
        if (stage == 5) {
            g.setColor(ACCENT); g.fillOval(73, -71, 40, 40);
            g.setColor(WHITE); g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(84, -51, 91, -44); g.drawLine(91, -44, 103, -59);
        }
        g.dispose();
    }
}

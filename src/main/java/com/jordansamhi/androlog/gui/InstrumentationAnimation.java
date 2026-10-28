package com.jordansamhi.androlog.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import static com.jordansamhi.androlog.gui.GuiTheme.*;

/** An APK opens, receives logging probes, and is rebuilt and sealed as the worker advances. */
final class InstrumentationAnimation extends JPanel {
    private static final Color EDGE = new Color(0x83B9C0);
    private final Timer timer;
    private long stageStarted = System.nanoTime(), transitionStarted = stageStarted;
    private boolean animate = true;
    private int stage;
    private double openingFrom, openingTo;

    InstrumentationAnimation() {
        setOpaque(false); setPreferredSize(new Dimension(580, 250));
        getAccessibleContext().setAccessibleName("APK package instrumentation");
        timer = new Timer(33, event -> { repaint(); updateTimer(); });
        addHierarchyListener(event -> updateTimer());
        updateDescription();
    }

    void setStage(int value) {
        int next = Math.max(0, Math.min(5, value));
        if (next == stage) return;
        openingFrom = opening();
        stage = next; openingTo = stage == 1 || stage == 2 ? 1 : 0;
        stageStarted = transitionStarted = System.nanoTime();
        if (!animate || stage == 0 || stage == 5) openingFrom = openingTo;
        updateDescription(); updateTimer(); repaint();
    }

    void setAnimationEnabled(boolean value) {
        if (animate != value) {
            openingFrom = opening(); animate = value;
            transitionStarted = stageStarted = System.nanoTime();
            if (!animate) openingFrom = openingTo;
        }
        updateTimer(); repaint();
    }

    @Override public void addNotify() { super.addNotify(); updateTimer(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

    private void updateTimer() {
        if (animate && isShowing() && stage > 0 && stage < 5) timer.start();
        else timer.stop();
    }

    private void updateDescription() {
        String[] descriptions = {"An Android APK package, ready to open", "Opening the APK and reading its code",
                "Adding logging statements to the APK code", "Rebuilding and closing the modified APK",
                "Aligning and signing the modified APK", "Modified APK rebuilt, signed, and ready to save"};
        getAccessibleContext().setAccessibleDescription(descriptions[stage]);
    }

    private double opening() {
        if (!animate) return openingTo;
        double progress = Math.min(1, (System.nanoTime() - transitionStarted) / 850_000_000.0);
        return openingFrom + (openingTo - openingFrom) * ease(progress);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = graphics(graphics);
        double time = animate ? (System.nanoTime() - stageStarted) / 1_000_000_000.0 : 0;
        double open = opening();
        double scale = Math.min(1.12, Math.min(getWidth() / 580.0, getHeight() / 250.0));
        g.translate(getWidth() / 2.0, getHeight() / 2.0 + 3);
        g.scale(scale, scale);

        // A quiet light pool anchors the package; no motion is used to imply percentage complete.
        g.setPaint(new RadialGradientPaint(new Point2D.Double(5, 15), 180, new float[]{0, 1},
                new Color[]{new Color(94, 201, 191, 42), new Color(94, 201, 191, 0)}));
        g.fill(new Ellipse2D.Double(-185, -115, 380, 238));
        for (int i = 4; i > 0; i--) {
            g.setColor(new Color(30, 78, 93, 4));
            g.fill(new Ellipse2D.Double(-110 - i * 3, 86 - i, 238 + i * 6, 17 + i * 3));
        }

        // Interior and rear lid are drawn behind the code, so the file visibly rises out of the package.
        Shape mouth = polygon(-97, 5, 82, 5, 121, -20, -58, -20);
        g.setColor(new Color(0x91B8BC)); g.fill(mouth);
        Shape rearLid = polygon(-58, -20, 121, -20, 116 + open * 9, -24 - open * 73,
                -63 + open * 9, -24 - open * 73);
        surface(g, rearLid, new Color(243, 253, 254, 235), new Color(175, 216, 223, 225), -100, EDGE);
        if (open > .01) drawCode(g, open, time);

        // Solid front and side give the illustration the silhouette of a real APK package.
        Shape side = polygon(82, 5, 121, -20, 121, 65, 82, 91);
        surface(g, side, new Color(201, 232, 235, 245), new Color(151, 198, 207, 245), 90, EDGE);
        Shape front = new RoundRectangle2D.Double(-97, 3, 180, 88, 9, 9);
        surface(g, front, new Color(248, 255, 255, 250), new Color(208, 234, 238, 248), 95, EDGE);
        g.setColor(new Color(255, 255, 255, 220)); g.draw(new Line2D.Double(-89, 8, 76, 8));

        // The split lid peels open to expose the editing area, then closes during rebuilding.
        Shape leftFlap = polygon(-97, 5, -58, -20, 31.5 - open * 138, -20 - open * 23,
                -7.5 - open * 138, 5 - open * 23);
        Shape rightFlap = polygon(82, 5, 121, -20, 31.5 + open * 138.5, -20 + open * 17,
                -7.5 + open * 138.5, 5 + open * 17);
        surface(g, leftFlap, new Color(252, 255, 255, 248), new Color(218, 240, 242, 245), 5, EDGE);
        surface(g, rightFlap, new Color(242, 253, 254, 248), new Color(199, 230, 235, 245), 5, EDGE);

        g.setFont(font(26, true)); g.setColor(INK); g.drawString("APK", -67, 47);
        g.setFont(font(10, false)); g.setColor(MUTED); g.drawString("Android package", -66, 66);
        g.setColor(new Color(255, 255, 255, 145));
        g.fill(new RoundRectangle2D.Double(36, 28, 28, 42, 7, 7));
        for (int i = 0; i < 3; i++) {
            g.setColor(stage >= 2 ? new Color(0x4EAAA1) : new Color(0xAAC7CB));
            g.fill(new RoundRectangle2D.Double(43, 36 + i * 10, 14, 3, 3, 3));
        }

        if (stage == 1 && open > .5) drawReading(g, time);
        if (stage == 2) drawInsertion(g, time, open);
        if (stage == 3) drawRebuild(g, time);
        if (stage >= 4) drawSeal(g, time, stage == 5);
        g.dispose();
    }

    private void drawCode(Graphics2D graphics, double open, double time) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setComposite(AlphaComposite.SrcOver.derive((float) open));
        g.translate(0, (1 - open) * 75);
        Shape file = new RoundRectangle2D.Double(-69, -81, 137, 111, 11, 11);
        g.setColor(new Color(25, 65, 83, 15)); g.fill(new RoundRectangle2D.Double(-66, -77, 137, 111, 11, 11));
        surface(g, file, new Color(255, 255, 255, 250), new Color(231, 246, 248, 250), 20, new Color(0x98C5CA));
        g.setColor(MUTED); g.setFont(font(10, true)); g.drawString("classes.dex", -55, -63);
        g.setColor(new Color(0xD1E5E7)); g.draw(new Line2D.Double(-56, -54, 55, -54));
        for (int row = 0; row < 5; row++) {
            float y = -42 + row * 12;
            boolean log = stage >= 2 && (row == 1 || row == 3);
            if (log) {
                float brightness = animate && stage == 2 ? (float) (.68 + .32 * Math.sin(time * 2.2 - row)) : 1;
                g.setColor(new Color(0, 126, 121, (int) (25 + 20 * brightness)));
                g.fill(new RoundRectangle2D.Double(-58, y - 7, 114, 11, 4, 4));
                g.setColor(ACCENT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
                g.drawString("+ log(\"...\")", -53, y + 1);
            } else {
                g.setColor(new Color(0x9AB9C2));
                g.fill(new RoundRectangle2D.Double(-54 + (row % 2) * 9, y - 3, row % 2 == 0 ? 83 : 62, 3, 3, 3));
            }
        }
        g.dispose();
    }

    private void drawReading(Graphics2D g, double time) {
        double scan = animate ? .5 + .5 * Math.sin(time * 2) : .45;
        double y = -52 + scan * 47;
        g.setPaint(new GradientPaint(0, (float) y - 6, new Color(48, 171, 168, 0), 0, (float) y, new Color(48, 171, 168, 80)));
        g.fill(new RoundRectangle2D.Double(-60, y - 6, 118, 7, 3, 3));
        g.setColor(new Color(0, 126, 121, 105)); g.draw(new Line2D.Double(-59, y, 58, y));
        caption(g, "Reading code", -230, -16);
        connector(g, -144, -12, -74, -40);
    }

    private void drawInsertion(Graphics2D g, double time, double open) {
        caption(g, "Adding logs", 152, -72);
        connector(g, 145, -56, 74, -35);
        double phase = animate ? (time / 2.25) % 1 : .1;
        double travel = ease(Math.max(0, Math.min(1, (phase - .12) / .68)));
        double x = 190 * (1 - travel) + 5 * travel;
        double y = -28 - Math.sin(travel * Math.PI) * 30;
        float alpha = (float) (open * (phase < .12 ? phase / .12 : phase > .8 ? (1 - phase) / .2 : 1));
        if (!animate) alpha = 1;
        Graphics2D chip = (Graphics2D) g.create();
        chip.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, Math.min(1, alpha))));
        chip.setColor(new Color(0, 103, 103, 15)); chip.fill(new RoundRectangle2D.Double(x - 31, y - 10, 64, 25, 12, 12));
        chip.setPaint(new GradientPaint(0, (float) y - 13, new Color(0x00877E), 0, (float) y + 11, new Color(0x006771)));
        chip.fill(new RoundRectangle2D.Double(x - 32, y - 13, 64, 25, 12, 12));
        chip.setColor(new Color(255, 255, 255, 145)); chip.draw(new RoundRectangle2D.Double(x - 32, y - 13, 64, 25, 12, 12));
        chip.setColor(WHITE); chip.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11)); chip.drawString("+ log", (float) x - 18, (float) y + 3);
        chip.dispose();
    }

    private void drawRebuild(Graphics2D g, double time) {
        caption(g, "Rebuilding APK", 147, -40);
        connector(g, 143, -31, 98, -1);
        double sweep = animate ? (time / 2.3) % 1 : .5;
        Graphics2D light = (Graphics2D) g.create();
        light.clip(new RoundRectangle2D.Double(-95, 5, 176, 84, 8, 8));
        float x = (float) (-180 + sweep * 365);
        light.setPaint(new GradientPaint(x, 0, new Color(255, 255, 255, 0), x + 32, 0, new Color(255, 255, 255, 145), true));
        light.fill(new Rectangle2D.Float(x, 5, 64, 84)); light.dispose();
    }

    private void drawSeal(Graphics2D g, double time, boolean done) {
        double pulse = animate && !done ? .5 + .5 * Math.sin(time * 2.4) : .5;
        g.setColor(new Color(0, 126, 121, (int) (12 + 10 * pulse)));
        g.fill(new Ellipse2D.Double(80 - pulse * 4, 43 - pulse * 4, 56 + pulse * 8, 56 + pulse * 8));
        g.setPaint(new GradientPaint(0, 46, new Color(0x138B80), 0, 94, new Color(0x006A75)));
        g.fill(new Ellipse2D.Double(85, 48, 46, 46));
        g.setColor(new Color(255, 255, 255, 190)); g.setStroke(new BasicStroke(1.3f));
        g.draw(new Ellipse2D.Double(86, 49, 44, 44));
        g.setColor(WHITE); g.setStroke(new BasicStroke(2.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (done) {
            g.draw(new Line2D.Double(97, 71, 105, 79)); g.draw(new Line2D.Double(105, 79, 119, 63));
        } else {
            g.draw(new Arc2D.Double(102, 58, 13, 16, 0, 180, Arc2D.OPEN));
            g.fill(new RoundRectangle2D.Double(99, 66, 19, 15, 4, 4));
            g.setColor(new Color(0x087B7C)); g.fill(new Ellipse2D.Double(107, 71, 3, 5));
        }
        caption(g, done ? "Ready to save" : "Signing APK", 150, -17);
        connector(g, 144, -8, 126, 38);
    }

    private static void caption(Graphics2D g, String text, int x, int y) {
        g.setColor(MUTED); g.setFont(font(11, false)); g.drawString(text, x, y);
    }

    private static void connector(Graphics2D g, int x1, int y1, int x2, int y2) {
        g.setColor(new Color(108, 160, 170, 95));
        g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{2, 4}, 0));
        g.draw(new QuadCurve2D.Double(x1, y1, x2, y1, x2, y2)); g.setStroke(new BasicStroke(1));
    }

    private static void surface(Graphics2D g, Shape shape, Color top, Color bottom, int bottomY, Color edge) {
        g.setPaint(new GradientPaint(-80, -75, top, 120, bottomY, bottom)); g.fill(shape);
        g.setColor(edge); g.setStroke(new BasicStroke(1.05f)); g.draw(shape);
    }

    private static Shape polygon(double... points) {
        Path2D path = new Path2D.Double(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.closePath(); return path;
    }

    private static double ease(double value) { return value * value * (3 - 2 * value); }
}

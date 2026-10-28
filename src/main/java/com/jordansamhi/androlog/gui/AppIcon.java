package com.jordansamhi.androlog.gui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Shared artwork for the window, Dock/taskbar, and in-app identity. */
final class AppIcon {
    private static final BufferedImage ARTWORK = read();
    private AppIcon() { }

    static ImageIcon icon(int size) {
        return new ImageIcon(new BaseMultiResolutionImage(scaled(size), scaled(size * 2), scaled(size * 3)));
    }

    static void install(JFrame frame) {
        frame.setIconImages(Arrays.asList(scaled(16), scaled(32), scaled(48), scaled(128), scaled(256)));
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(ARTWORK);
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Window and packaged-app icons still work when the desktop has no taskbar API.
        }
    }

    private static BufferedImage read() {
        try (InputStream input = AppIcon.class.getResourceAsStream("/gui/androlog-icon.png")) {
            if (input != null) {
                BufferedImage image = ImageIO.read(input);
                if (image != null) return image;
            }
        } catch (IOException ignored) { /* Keep the application usable if artwork cannot be read. */ }
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage scaled(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(ARTWORK, 0, 0, size, size, null); g.dispose();
        return image;
    }
}

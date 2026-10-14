package cn.homewatch;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** The desktop window and the Windows launcher use the same original recorder mark. */
public final class AppIdentity {
    public static final String NAME = "录像机";
    public static final String VERSION = "1.4.1";
    public static final String AUTHOR = "千夏Tesni";
    public static final String QQ = "2758399324";
    public static final String EMAIL = "yachi8000@yachiyo.cn";
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static final List<Image> ICONS = loadIcons();

    private AppIdentity() {}

    public static List<Image> icons() { return ICONS; }

    private static List<Image> loadIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage icon = null;
            try (InputStream resource = AppIdentity.class.getResourceAsStream("/cn/homewatch/icons/recorder-" + size + ".png")) {
                if (resource != null) icon = ImageIO.read(resource);
            } catch (IOException ignored) { /* A bare classpath still gets the matching vector mark. */ }
            if (icon == null || icon.getWidth() != size || icon.getHeight() != size) icon = drawIcon(size);
            icons.add(icon);
        }
        return List.copyOf(icons);
    }

    public static BufferedImage drawIcon(int size) {
        int raster = Math.max(256, size * 4);
        BufferedImage image = new BufferedImage(raster, raster, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(raster / 256.0, raster / 256.0);
            g.setColor(new Color(43, 46, 53));
            g.fill(new RoundRectangle2D.Double(8, 8, 240, 240, 52, 52));
            g.setColor(new Color(248, 249, 250));
            g.fill(new RoundRectangle2D.Double(47, 54, 120, 100, 22, 22));
            Path2D hood = new Path2D.Double();
            hood.moveTo(170, 78); hood.lineTo(207, 62); hood.quadTo(214, 59, 214, 68);
            hood.lineTo(214, 140); hood.quadTo(214, 149, 207, 146); hood.lineTo(170, 130); hood.closePath();
            g.fill(hood);
            g.setColor(new Color(43, 46, 53));
            g.fill(new Ellipse2D.Double(69, 74, 60, 60));
            g.setColor(new Color(215, 45, 51));
            g.fill(new Ellipse2D.Double(78, 83, 42, 42));
            g.setColor(new Color(248, 249, 250));
            g.fill(new Ellipse2D.Double(89, 94, 12, 12));
            g.fill(new RoundRectangle2D.Double(40, 178, 176, 37, 11, 11));
            g.setColor(new Color(215, 45, 51));
            g.fill(new Ellipse2D.Double(54, 189, 15, 15));
            g.setColor(new Color(43, 46, 53));
            g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(146, 197, 160, 197); g.drawLine(176, 197, 190, 197);
        } finally { g.dispose(); }
        if (raster == size) return image;
        BufferedImage resized = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D scaled = resized.createGraphics();
        try {
            scaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            scaled.drawImage(image, 0, 0, size, size, null);
        } finally { scaled.dispose(); }
        return resized;
    }
}

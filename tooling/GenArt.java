import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Generates the TV banner (320x180 + xhdpi 2x) and the adaptive-icon
 * foreground layer (ring + monogram on transparency; the background gradient
 * lives in drawable/ic_launcher_bg.xml). Design space is fixed — output
 * images are the design scaled via Graphics2D.scale, so text stays sharp.
 *
 *  java tooling/GenArt.java app/src/main/res   */
public class GenArt {
    static final Color DEEP = new Color(0x07, 0x14, 0x26);
    static final Color DEEP_LIGHT = new Color(0x0A, 0x2A, 0x4D);
    static final Color GOLD = new Color(0xD8, 0xB1, 0x5A);
    static final Color EMBER = new Color(0xE0, 0x64, 0x2B);

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File res = new File(args.length > 0 ? args[0] : ".");
        // Every bitmap in every density bucket: crisp on any TV, and lint's
        // density-completeness checks have nothing to complain about.
        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        double[] bannerScales = {1.0, 1.5, 2.0, 3.0, 4.0};
        double[] iconScales = {48 / 192.0, 72 / 192.0, 96 / 192.0, 144 / 192.0, 1.0};
        for (int i = 0; i < densities.length; i++) {
            File dir = new File(res, "drawable-" + densities[i]);
            dir.mkdirs();
            ImageIO.write(banner(bannerScales[i]), "png", new File(dir, "tv_banner.png"));
            ImageIO.write(iconForeground(iconScales[i]), "png", new File(dir, "ic_launcher_fg.png"));
        }
        System.out.println("wrote banner + icon foreground at 5 densities to " + res.getAbsolutePath());
    }

    static BufferedImage banner(double scale) {
        int w = (int) (320 * scale), h = (int) (180 * scale);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(scale, scale); // design space: 320x180

        g.setPaint(new GradientPaint(0, 0, DEEP, 0, 180, DEEP_LIGHT));
        g.fillRect(0, 0, 320, 180);

        // drifting motes: a few soft gold dots + one ember accent
        int[][] motes = {{36, 30, 4, 110}, {278, 44, 3, 90}, {58, 148, 3, 80},
                         {250, 140, 4, 100}, {160, 22, 2, 120}};
        for (int[] m : motes) {
            g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), m[3]));
            g.fillOval(m[0], m[1], m[2], m[2]);
        }
        g.setColor(new Color(EMBER.getRed(), EMBER.getGreen(), EMBER.getBlue(), 120));
        g.fillOval(300, 96, 3, 3);

        Font wordmark = new Font("SansSerif", Font.BOLD, 33);
        g.setFont(wordmark);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(GOLD);
        center(g, fm, "INWARD", 66);
        center(g, fm, "JOURNEY", 116);

        int ruleW = 120;
        g.fillRect((320 - ruleW) / 2, 134, ruleW, 2);

        g.dispose();
        return img;
    }

    /** Adaptive-icon foreground: gold ring + IJ + ember dot on transparency.
     *  Design space 192x192; content stays inside the ~66% adaptive safe zone. */
    static BufferedImage iconForeground(double scale) {
        int s = (int) (192 * scale);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(scale, scale); // design space: 192x192

        g.setStroke(new BasicStroke(5f));
        g.setColor(GOLD);
        g.drawOval(34, 34, 192 - 68, 192 - 68);

        g.setFont(new Font("SansSerif", Font.BOLD, 64));
        FontMetrics fm = g.getFontMetrics();
        String monogram = "IJ";
        g.setColor(GOLD);
        g.drawString(monogram, (192 - fm.stringWidth(monogram)) / 2,
                (192 - fm.getHeight()) / 2 + fm.getAscent());

        g.setColor(EMBER);
        g.fillOval(192 / 2 - 4, 192 - 44, 8, 8);

        g.dispose();
        return img;
    }

    static void center(Graphics2D g, FontMetrics fm, String text, int y) {
        g.drawString(text, (320 - fm.stringWidth(text)) / 2, y);
    }
}

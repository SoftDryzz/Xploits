import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Renders the Xploits artwork from the console logo ({@code logo.ans}), so the icon, the README banner and
 * GitHub's social preview are the same logo the console draws. Each half-block character is two pixels, one
 * above the other; they are scaled by a whole number with no smoothing. Anything in the logo it cannot read
 * stops it: a broken logo is never drawn as if it were the good one.
 *
 * <p>Run from the repository root: {@code java tools/ArtRenderer.java}.
 */
public final class ArtRenderer {
    private static final Path LOGO = Path.of("src/main/resources/xploits/console/logo.ans");
    private static final Path ICON = Path.of("src/main/resources/assets/xploits/icon.png");
    private static final Path BANNER = Path.of("docs/images/banner.png");
    private static final Path SOCIAL = Path.of("docs/images/social-preview.png");

    private static final Pattern SGR = Pattern.compile("\u001b\\[([0-9;]*)m");
    private static final int X_COLUMNS = 14;
    private static final Color PURPLE = new Color(0xAF, 0x00, 0xFF);
    private static final Color CENTER = new Color(0x22, 0x12, 0x33);
    private static final Color EDGE = new Color(0x07, 0x06, 0x0B);
    private static final Color TAGLINE = new Color(0xD8, 0xD0, 0xE6);
    private static final Color MUTED = new Color(0x9A, 0x8F, 0xAD);
    private static final String FONT = "Segoe UI";

    private ArtRenderer() {
    }

    public static void main(String[] args) throws IOException {
        Color[][] logo = trim(parse(Files.readString(LOGO, StandardCharsets.UTF_8)));
        write(icon(firstLetter(logo)), ICON);
        write(banner(logo), BANNER);
        write(social(logo), SOCIAL);
    }

    /** The logo as pixels, top to bottom; {@code null} is transparent. */
    static Color[][] parse(String ans) {
        List<String> rows = ans.replace("\r", "").lines().toList();
        int width = 0;
        List<List<Color[]>> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            List<Color[]> line = new ArrayList<>();
            Color fg = null;
            Color bg = null;
            int i = 0;
            while (i < row.length()) {
                Matcher m = SGR.matcher(row).region(i, row.length());
                if (m.lookingAt()) {
                    Color[] state = apply(m.group(1), fg, bg, r);
                    fg = state[0];
                    bg = state[1];
                    i = m.end();
                    continue;
                }
                char c = row.charAt(i++);
                switch (c) {
                    case ' ' -> line.add(new Color[] {bg, bg});
                    case '▀' -> line.add(new Color[] {need(fg, r), bg});
                    case '▄' -> line.add(new Color[] {bg, need(fg, r)});
                    default -> throw new IllegalArgumentException("row " + (r + 1) + " has a character the renderer does not know: U+" + Integer.toHexString(c));
                }
            }
            width = Math.max(width, line.size());
            cells.add(line);
        }
        Color[][] px = new Color[rows.size() * 2][width];
        for (int r = 0; r < cells.size(); r++) {
            List<Color[]> line = cells.get(r);
            for (int x = 0; x < line.size(); x++) {
                px[r * 2][x] = line.get(x)[0];
                px[r * 2 + 1][x] = line.get(x)[1];
            }
        }
        return px;
    }

    private static Color[] apply(String params, Color fg, Color bg, int row) {
        String[] p = params.isEmpty() ? new String[] {"0"} : params.split(";");
        for (int i = 0; i < p.length; i++) {
            switch (p[i]) {
                case "0" -> {
                    fg = null;
                    bg = null;
                }
                case "39" -> fg = null;
                case "49" -> bg = null;
                case "38", "48" -> {
                    if (i + 2 >= p.length || !p[i + 1].equals("5")) {
                        throw new IllegalArgumentException("row " + (row + 1) + " has a color that is not a 256-color one: " + params);
                    }
                    Color c = xterm(Integer.parseInt(p[i + 2]));
                    if (p[i].equals("38")) fg = c; else bg = c;
                    i += 2;
                }
                default -> throw new IllegalArgumentException("row " + (row + 1) + " has an escape the renderer does not know: " + params);
            }
        }
        return new Color[] {fg, bg};
    }

    private static Color need(Color fg, int row) {
        if (fg == null) throw new IllegalArgumentException("row " + (row + 1) + " draws a half block with no color");
        return fg;
    }

    /** The xterm 256-color palette. */
    static Color xterm(int n) {
        if (n < 0 || n > 255) throw new IllegalArgumentException("color out of range: " + n);
        if (n < 16) {
            int[] base = {0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xC0C0C0,
                0x808080, 0xFF0000, 0x00FF00, 0xFFFF00, 0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF};
            return new Color(base[n]);
        }
        if (n < 232) {
            int[] level = {0, 95, 135, 175, 215, 255};
            int i = n - 16;
            return new Color(level[i / 36], level[(i / 6) % 6], level[i % 6]);
        }
        int g = 8 + 10 * (n - 232);
        return new Color(g, g, g);
    }

    /** The smallest box around the drawn pixels. */
    static Color[][] trim(Color[][] px) {
        int top = px.length, bottom = -1, left = px[0].length, right = -1;
        for (int y = 0; y < px.length; y++) {
            for (int x = 0; x < px[y].length; x++) {
                if (px[y][x] == null) continue;
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        if (bottom < 0) throw new IllegalArgumentException("the logo has no pixels");
        Color[][] out = new Color[bottom - top + 1][right - left + 1];
        for (int y = top; y <= bottom; y++) System.arraycopy(px[y], left, out[y - top], 0, right - left + 1);
        return out;
    }

    /**
     * The X: the logo's first {@link #X_COLUMNS} columns. The letters touch, so there is no empty column to
     * split on; the width was measured on the logo (the P starts at the next column).
     */
    static Color[][] firstLetter(Color[][] px) {
        if (px[0].length <= X_COLUMNS) throw new IllegalArgumentException("the logo is narrower than its X");
        Color[][] out = new Color[px.length][X_COLUMNS];
        for (int y = 0; y < px.length; y++) System.arraycopy(px[y], 0, out[y], 0, X_COLUMNS);
        return trim(out);
    }

    static BufferedImage scaled(Color[][] px, int scale) {
        BufferedImage img = new BufferedImage(px[0].length * scale, px.length * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < px.length; y++) {
            for (int x = 0; x < px[y].length; x++) {
                if (px[y][x] == null) continue;
                int argb = px[y][x].getRGB();
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) img.setRGB(x * scale + dx, y * scale + dy, argb);
                }
            }
        }
        return img;
    }

    /** A soft halo in {@code tint} around the drawn pixels of {@code art}, {@code pad} pixels wider on each side. */
    static BufferedImage glow(BufferedImage art, Color tint, int radius, int pad) {
        BufferedImage mask = new BufferedImage(art.getWidth() + 2 * pad, art.getHeight() + 2 * pad, BufferedImage.TYPE_INT_ARGB);
        int rgb = tint.getRGB() & 0xFFFFFF;
        for (int y = 0; y < art.getHeight(); y++) {
            for (int x = 0; x < art.getWidth(); x++) {
                if ((art.getRGB(x, y) >>> 24) != 0) mask.setRGB(x + pad, y + pad, 0xFF000000 | rgb);
            }
        }
        float[] k = gaussian(radius);
        ConvolveOp h = new ConvolveOp(new Kernel(k.length, 1, k), ConvolveOp.EDGE_NO_OP, null);
        ConvolveOp v = new ConvolveOp(new Kernel(1, k.length, k), ConvolveOp.EDGE_NO_OP, null);
        return v.filter(h.filter(mask, null), null);
    }

    private static float[] gaussian(int radius) {
        float sigma = radius / 2.5f;
        float[] k = new float[radius * 2 + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            k[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            sum += k[i + radius];
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        return k;
    }

    private static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return g;
    }

    /** Dark purple at the centre fading to near black at the edges. */
    private static void background(Graphics2D g, int w, int h) {
        float radius = Math.max(w, h) * 0.75f;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, h / 2f), radius, new float[] {0f, 1f}, new Color[] {CENTER, EDGE}));
        g.fillRect(0, 0, w, h);
    }

    /** Draws {@code art} with its halo, centred horizontally, its top at {@code y}. */
    private static void drawWithGlow(Graphics2D g, BufferedImage art, int canvasWidth, int y, int radius) {
        int x = (canvasWidth - art.getWidth()) / 2;
        BufferedImage halo = glow(art, PURPLE, radius, radius * 2);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.drawImage(halo, x - radius * 2, y - radius * 2, null);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(art, x, y, null);
    }

    private static void centredText(Graphics2D g, String text, Font font, Color color, int width, int baseline) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (width - fm.stringWidth(text)) / 2f, baseline);
    }

    static BufferedImage icon(Color[][] letter) {
        int size = 256;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        g.setClip(new RoundRectangle2D.Float(0, 0, size, size, 56, 56));
        background(g, size, size);
        int scale = Math.min((size - 64) / letter[0].length, (size - 48) / letter.length);
        BufferedImage art = scaled(letter, scale);
        drawWithGlow(g, art, size, (size - art.getHeight()) / 2, 18);
        g.dispose();
        return img;
    }

    static BufferedImage banner(Color[][] logo) {
        int w = 1280, h = 400;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        background(g, w, h);
        BufferedImage art = scaled(logo, 10);
        drawWithGlow(g, art, w, 56, 28);
        centredText(g, "Meteor Client addon for anarchy servers", new Font(FONT, Font.PLAIN, 30), TAGLINE, w, 56 + art.getHeight() + 62);
        g.dispose();
        return img;
    }

    static BufferedImage social(Color[][] logo) {
        int w = 1280, h = 640;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        background(g, w, h);
        BufferedImage art = scaled(logo, 10);
        int top = 150;
        drawWithGlow(g, art, w, top, 28);
        centredText(g, "Meteor Client addon for anarchy servers", new Font(FONT, Font.PLAIN, 34), TAGLINE, w, top + art.getHeight() + 76);
        centredText(g, "crystal-aura++  ·  auto-pvp  ·  auto-travel  ·  nether-sweep  ·  fight-recorder",
            new Font(FONT, Font.PLAIN, 24), MUTED, w, top + art.getHeight() + 128);
        g.dispose();
        return img;
    }

    private static void write(BufferedImage img, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        if (!ImageIO.write(img, "png", out.toFile())) throw new IOException("no PNG writer for " + out);
        System.out.println(out + "  " + img.getWidth() + "x" + img.getHeight() + "  " + Files.size(out) / 1024 + " KB");
    }
}

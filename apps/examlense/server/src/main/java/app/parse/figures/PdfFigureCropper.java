package app.parse.figures;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * Turns a page-space rectangle into PNG bytes cut from the rendered page.
 *
 * <p>We crop the render rather than pulling the image XObject's own bytes out.
 * That keeps soft-mask alpha correctly composited, puts raster and vector
 * figures through one code path, and captures a figure that is a screenshot with
 * a vector callout drawn on top. It also guarantees RGB PNG output, which is
 * already in the extension set {@code FigureController} accepts.
 */
@Component
public class PdfFigureCropper {

    /**
     * A4 at 200 DPI is ~15 MB of heap; 300 DPI would be ~35 MB for resolution the
     * source (96-DPI paste, or vector art) usually does not have.
     */
    public static final float DPI = 200f;

    private static final float PAD_POINTS = 4f;
    private static final int MIN_PX = 32;
    private static final int MAX_PX = 4000;
    private static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    private static final double MIN_INK_FRACTION = 0.005;
    private static final int BACKGROUND_LUMA = 250;
    private static final int LUMA_BUCKETS = 16;

    public record Crop(byte[] png, int width, int height) {}

    /**
     * Maps a rect from PDF user space onto the rendered page bitmap.
     *
     * <p>Two things bite here. {@code renderImageWithDPI} renders the
     * <em>CropBox</em>, so page-space coordinates must be taken relative to its
     * lower-left corner rather than to (0,0). And the renderer applies the page's
     * {@code /Rotate}, while the content stream — and therefore every rectangle we
     * collected — is pre-rotation. All four corners are mapped and re-bounded so
     * one expression covers every rotation.
     */
    static Rectangle toPixels(Rectangle2D pageRect, PDPage page, float dpi) {
        PDRectangle crop = page.getCropBox();
        double scale = dpi / 72.0;
        int rotation = ((page.getRotation() % 360) + 360) % 360;

        double[] xs = new double[4];
        double[] ys = new double[4];
        double[][] corners = {
            {pageRect.getMinX(), pageRect.getMinY()},
            {pageRect.getMaxX(), pageRect.getMinY()},
            {pageRect.getMaxX(), pageRect.getMaxY()},
            {pageRect.getMinX(), pageRect.getMaxY()},
        };
        for (int i = 0; i < 4; i++) {
            Point2D p = mapCorner(corners[i][0], corners[i][1], crop, rotation);
            xs[i] = p.getX() * scale;
            ys[i] = p.getY() * scale;
        }
        double minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        double maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));

        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        return new Rectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y);
    }

    /** Page point → un-scaled bitmap point (origin top-left, y down). */
    private static Point2D mapCorner(double x, double y, PDRectangle crop, int rotation) {
        double u = x - crop.getLowerLeftX();
        double v = y - crop.getLowerLeftY();
        double w = crop.getWidth();
        double h = crop.getHeight();
        return switch (rotation) {
            case 90 -> new Point2D.Double(v, u);
            case 180 -> new Point2D.Double(w - u, v);
            case 270 -> new Point2D.Double(h - v, w - u);
            default -> new Point2D.Double(u, h - v);
        };
    }

    /** Grows the rect by {@link #PAD_POINTS}, clamped to the page's CropBox. */
    static Rectangle2D pad(Rectangle2D pageRect, PDPage page) {
        PDRectangle crop = page.getCropBox();
        Rectangle2D box = new Rectangle2D.Double(
            crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());
        Rectangle2D grown = new Rectangle2D.Double(
            pageRect.getX() - PAD_POINTS, pageRect.getY() - PAD_POINTS,
            pageRect.getWidth() + 2 * PAD_POINTS, pageRect.getHeight() + 2 * PAD_POINTS);
        return grown.createIntersection(box);
    }

    /**
     * Cuts {@code region} out of an already-rendered page, or empty when the
     * result fails a plausibility check. Every rejection leaves the figure block
     * empty, which is exactly the "upload a screenshot" state users have today —
     * so bailing is always safe, and shipping a wrong crop never is.
     */
    public Optional<Crop> crop(BufferedImage pageImage, PDPage page, Rectangle2D region) {
        Rectangle px = toPixels(pad(region, page), page, DPI)
            .intersection(new Rectangle(0, 0, pageImage.getWidth(), pageImage.getHeight()));
        if (px.isEmpty() || px.width < MIN_PX || px.height < MIN_PX) return Optional.empty();
        if (px.width > MAX_PX || px.height > MAX_PX) return Optional.empty();

        BufferedImage cut = pageImage.getSubimage(px.x, px.y, px.width, px.height);
        if (!hasContent(cut)) return Optional.empty();

        return encode(cut);
    }

    /**
     * Rejects crops with (almost) no ink and crops of a single flat tone.
     *
     * <p>The ink test is what catches a JPX/JBIG2 image that PDFBox cannot decode:
     * those render as blank rather than throwing, so without this the pipeline
     * would happily store an empty white rectangle.
     *
     * <p>Note we only require <em>two</em> occupied luminance buckets. Line art —
     * a TikZ export, a schematic — legitimately uses barely more than black and
     * white, so a higher bar would reject the very figures this feature is for.
     */
    private static boolean hasContent(BufferedImage img) {
        long ink = 0;
        long total = (long) img.getWidth() * img.getHeight();
        boolean[] buckets = new boolean[LUMA_BUCKETS];
        int distinct = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                if (luma < BACKGROUND_LUMA) ink++;
                int bucket = luma * LUMA_BUCKETS / 256;
                if (!buckets[bucket]) {
                    buckets[bucket] = true;
                    distinct++;
                }
            }
        }
        return distinct >= 2 && (double) ink / total >= MIN_INK_FRACTION;
    }

    /** PNG (lossless — line art and UI screenshots are what JPEG ruins), downscaled if oversized. */
    private static Optional<Crop> encode(BufferedImage img) {
        BufferedImage current = img;
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] png = toPng(current);
            if (png == null) return Optional.empty();
            if (png.length <= MAX_PNG_BYTES) {
                return Optional.of(new Crop(png, current.getWidth(), current.getHeight()));
            }
            current = downscale(current, 0.75);
            if (current.getWidth() < MIN_PX || current.getHeight() < MIN_PX) break;
        }
        return Optional.empty();
    }

    private static byte[] toPng(BufferedImage img) {
        try {
            // getSubimage shares the parent raster; copy so PNG encoding sees only the crop.
            BufferedImage copy = new BufferedImage(
                img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(copy, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage downscale(BufferedImage img, double factor) {
        int w = Math.max(1, (int) (img.getWidth() * factor));
        int h = Math.max(1, (int) (img.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}

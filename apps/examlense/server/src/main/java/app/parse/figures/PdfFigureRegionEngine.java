package app.parse.figures;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the page-space footprint of every drawing operation, so a figure can
 * be located by geometry rather than by asking a model where it is.
 *
 * <p>Walking {@code PDResources.getXObjectNames()} instead would be wrong three
 * ways: it yields an image's intrinsic pixel size but no placement rectangle, it
 * cannot see anything nested inside a Form XObject (which is exactly how
 * {@code \includegraphics} and docx4j/FOP emit figures), and it cannot tell one
 * placement from five of the same object. Running the content stream solves all
 * three at once, because {@code showForm} recurses with the CTM composed.
 *
 * <p>Two PDFBox behaviours this relies on: path callbacks receive coordinates
 * that {@code transformedPoint} has <em>already</em> mapped into user space, so
 * the CTM must not be applied again; {@code drawImage} does not, so it reads the
 * CTM itself. Overriding {@code showGlyph} gets text boxes in the same pass and
 * the same coordinate space — {@code PDFTextStripper}'s {@code *DirAdj}
 * accessors live in a flipped, rotation-normalised space that does not mix with
 * these coordinates.
 */
final class PdfFigureRegionEngine extends PDFGraphicsStreamEngine {

    /**
     * A pathological vector page (a dense map, a scatter plot with 10k marks)
     * can emit millions of path ops. We only ever use paths as weak candidates,
     * so capping the collection bounds memory without changing any outcome.
     */
    private static final int MAX_OPS = 50_000;

    /**
     * A form whose drawn content sits mostly outside its declared BBox means our
     * BBox transform disagrees with the renderer's. Rather than emit a rectangle
     * derived from arithmetic we cannot verify, fall back to the drawn union.
     */
    private static final double BBOX_AGREEMENT = 0.5;

    private final List<FigureRegion> regions = new ArrayList<>();
    private final GeneralPath path = new GeneralPath();
    private boolean truncated;

    PdfFigureRegionEngine(PDPage page) {
        super(page);
    }

    /** Runs the page's content stream and returns everything it drew. */
    static List<FigureRegion> scan(PDPage page) throws IOException {
        PdfFigureRegionEngine engine = new PdfFigureRegionEngine(page);
        engine.processPage(page);
        return List.copyOf(engine.regions);
    }

    List<FigureRegion> regions() {
        return List.copyOf(regions);
    }

    boolean truncated() {
        return truncated;
    }

    // --- images -----------------------------------------------------------

    @Override
    public void drawImage(PDImage image) {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        // The PDF imaging model maps the unit square onto the placement rect.
        Rectangle2D placed = ctm.createAffineTransform()
            .createTransformedShape(new Rectangle2D.Float(0, 0, 1, 1))
            .getBounds2D();
        add(new FigureRegion(placed, FigureRegion.Kind.RASTER, keyOf(image)));
    }

    private static String keyOf(PDImage image) {
        return image instanceof PDImageXObject x
            ? Integer.toHexString(System.identityHashCode(x.getCOSObject()))
            : null;
    }

    // --- forms ------------------------------------------------------------

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        captureForm(form, () -> super.showForm(form));
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
        captureForm(group, () -> super.showTransparencyGroup(group));
    }

    /**
     * Emits one FORM region covering what the form actually drew.
     *
     * <p>The union of drawn content is the trustworthy signal: it arrives already
     * transformed by the children's own callbacks, so it needs no matrix maths of
     * ours. The declared {@code /BBox} is only used to tighten it — forms often
     * declare a generous box — and only when the two agree, so an incorrect BBox
     * transform degrades to the drawn union instead of producing a bogus rect.
     */
    private void captureForm(PDFormXObject form, IoRunnable body) throws IOException {
        int mark = regions.size();
        Matrix parentCtm = getGraphicsState().getCurrentTransformationMatrix().clone();
        body.run();
        if (regions.size() <= mark) return;

        Rectangle2D drawn = union(regions.subList(mark, regions.size()));
        if (drawn == null || drawn.isEmpty()) return;

        Rectangle2D declared = declaredBox(form, parentCtm);
        Rectangle2D rect = drawn;
        if (declared != null && !declared.isEmpty()) {
            Rectangle2D clipped = declared.createIntersection(drawn);
            if (!clipped.isEmpty() && area(clipped) >= BBOX_AGREEMENT * area(drawn)) {
                rect = clipped;
            }
        }
        add(new FigureRegion(rect, FigureRegion.Kind.FORM,
            Integer.toHexString(System.identityHashCode(form.getCOSObject()))));
    }

    private static Rectangle2D declaredBox(PDFormXObject form, Matrix parentCtm) {
        PDRectangle bbox = form.getBBox();
        if (bbox == null) return null;
        Matrix formMatrix = form.getMatrix();
        Matrix full = formMatrix == null ? parentCtm : Matrix.concatenate(formMatrix, parentCtm);
        return bbox.transform(full).getBounds2D();
    }

    // --- paths ------------------------------------------------------------
    // Coordinates arrive already CTM-transformed; do not transform them again.

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        path.lineTo(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public void closePath() {
        if (path.getCurrentPoint() != null) path.closePath();
    }

    @Override
    public Point2D getCurrentPoint() {
        return path.getCurrentPoint();
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        path.moveTo(p0.getX(), p0.getY());
        path.lineTo(p1.getX(), p1.getY());
        path.lineTo(p2.getX(), p2.getY());
        path.lineTo(p3.getX(), p3.getY());
        path.closePath();
    }

    /** A path consumed by {@code n} (or {@code W n}) paints nothing. */
    @Override
    public void endPath() {
        path.reset();
    }

    @Override
    public void clip(int windingRule) {
        // No-op: PDFBox calls endPath() straight after, which resets the path.
    }

    @Override
    public void strokePath() {
        emitPath();
    }

    @Override
    public void fillPath(int windingRule) {
        emitPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        emitPath();
    }

    @Override
    public void shadingFill(COSName shadingName) {
        // A shading fill covers the current clip; the clip bounds are the best
        // approximation available without evaluating the shading function.
        Area clip = getGraphicsState().getCurrentClippingPath();
        if (clip != null && !clip.isEmpty()) {
            add(new FigureRegion(clip.getBounds2D(), FigureRegion.Kind.VECTOR));
        }
    }

    private void emitPath() {
        if (path.getCurrentPoint() == null && path.getBounds2D().isEmpty()) {
            path.reset();
            return;
        }
        // Inflate by half the line width so a hairline stroke isn't a zero-area rect.
        double half = getGraphicsState().getLineWidth() / 2 + 0.25;
        Rectangle2D b = path.getBounds2D();
        add(new FigureRegion(new Rectangle2D.Double(
            b.getX() - half, b.getY() - half,
            b.getWidth() + 2 * half, b.getHeight() + 2 * half), FigureRegion.Kind.VECTOR));
        path.reset();
    }

    // --- text -------------------------------------------------------------

    @Override
    protected void showGlyph(Matrix trm, PDFont font, int code, Vector displacement)
        throws IOException {
        float height = Math.abs(trm.getScalingFactorY());
        float width = Math.abs(displacement.getX() * trm.getScalingFactorX());
        if (height > 0 && width > 0) {
            // trm translates to the baseline origin; drop a fifth of the size for descenders.
            add(new FigureRegion(new Rectangle2D.Float(
                trm.getTranslateX(), trm.getTranslateY() - height * 0.2f,
                width, height), FigureRegion.Kind.GLYPH, null, decode(font, code)));
        }
        super.showGlyph(trm, font, code, displacement);
    }

    /** A font without a usable ToUnicode map yields no text; geometry still works. */
    private static String decode(PDFont font, int code) {
        try {
            return font == null ? null : font.toUnicode(code);
        } catch (Exception e) {
            return null;
        }
    }

    // --- helpers ----------------------------------------------------------

    private void add(FigureRegion region) {
        if (regions.size() >= MAX_OPS) {
            truncated = true;
            return;
        }
        Rectangle2D r = region.rect();
        if (Double.isNaN(r.getX()) || Double.isNaN(r.getY())
            || Double.isNaN(r.getWidth()) || Double.isNaN(r.getHeight())
            || Double.isInfinite(r.getWidth()) || Double.isInfinite(r.getHeight())) {
            return;
        }
        regions.add(region);
    }

    static Rectangle2D union(List<FigureRegion> parts) {
        Rectangle2D acc = null;
        for (FigureRegion p : parts) {
            if (p.rect().isEmpty()) continue;
            acc = acc == null ? (Rectangle2D) p.rect().clone() : acc.createUnion(p.rect());
        }
        return acc;
    }

    private static double area(Rectangle2D r) {
        return Math.max(0, r.getWidth()) * Math.max(0, r.getHeight());
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }
}

package app.parse.figures;

import java.awt.geom.Rectangle2D;

/**
 * The footprint of one drawing operation on a page, in PDF user space (points,
 * origin bottom-left, before any {@code /Rotate}).
 *
 * <p>{@code xobjectKey} identifies the underlying XObject so a letterhead drawn
 * at the same spot on every page can be recognised and demoted; it is null for
 * path and glyph operations, which have no reusable identity. {@code text} is the
 * decoded character, set only for {@link Kind#GLYPH}, and is what lets a caption
 * line be found without a second {@code PDFTextStripper} pass in a different
 * coordinate space.
 */
record FigureRegion(Rectangle2D rect, Kind kind, String xobjectKey, String text) {

    enum Kind {
        /** An image XObject painted by {@code Do}. */
        RASTER,
        /** A Form XObject: its declared BBox intersected with what it actually drew. */
        FORM,
        /** A stroked or filled path. Collected as a candidate only — never clustered. */
        VECTOR,
        /** One glyph's box, used to tell body text from artwork. */
        GLYPH
    }

    FigureRegion(Rectangle2D rect, Kind kind) {
        this(rect, kind, null, null);
    }

    FigureRegion(Rectangle2D rect, Kind kind, String xobjectKey) {
        this(rect, kind, xobjectKey, null);
    }

    double area() {
        return Math.max(0, rect.getWidth()) * Math.max(0, rect.getHeight());
    }

    boolean isArtwork() {
        return kind == Kind.RASTER || kind == Kind.FORM;
    }
}

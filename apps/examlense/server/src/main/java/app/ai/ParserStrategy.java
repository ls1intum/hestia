package app.ai;

/**
 * Mirror of supabase/functions/_shared/parser-strategies.ts.
 */
public record ParserStrategy(
    String id,
    String label,
    String description,
    String providerModel,
    PdfMode pdfMode,
    ProviderKind providerKind
) {
    public enum PdfMode {
        PDF_DIRECT,
        RASTERIZE,
        TEXT_ONLY
    }
}

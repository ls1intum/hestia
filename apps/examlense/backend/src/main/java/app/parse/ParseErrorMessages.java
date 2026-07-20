package app.parse;

/**
 * Central catalog of user-facing PDF-parse failure messages. These strings are
 * persisted verbatim to {@code exams.parse_error} and shown to the author in the
 * dashboard, the editor error screen, and a snackbar — so every message states
 * what went wrong and a concrete next step. Keep them provider-agnostic ("the AI
 * service") since the parser model is pinned server-side.
 */
public final class ParseErrorMessages {

    private ParseErrorMessages() {}

    // --- PDF pre-checks ---
    public static final String PDF_OPEN_FAILED =
        "We couldn't open the uploaded file. Please re-upload the PDF and try again.";
    public static final String PDF_TOO_LARGE =
        "This PDF is larger than the 10 MB limit. Please upload a smaller file "
        + "(compress or split it) and retry.";
    public static final String PDF_INVALID =
        "This file doesn't look like a valid PDF. Please upload a proper PDF exam and retry.";
    public static final String PDF_UNREADABLE =
        "We couldn't read this PDF's contents — it may be corrupted, password-protected, "
        + "or an image-only scan. Please re-export or unlock it (or upload a text-based PDF) and retry.";

    // --- Parsed result doesn't fit an exam structure ---
    public static final String NO_TASKS =
        "We couldn't find any exam questions in this PDF. Please make sure it's an actual exam "
        + "with tasks or questions — not a syllabus, slides, or an image-only scan — then retry, "
        + "or upload a clearer copy.";
    public static final String NOT_STRUCTURED =
        "The AI read the file but couldn't turn it into a structured exam. This usually happens "
        + "with unusual layouts or scanned images. Please retry, or upload a text-based PDF of the exam.";

    // --- AI service reachability ---
    public static final String AI_RATE_LIMIT =
        "The AI service is busy right now (rate limit reached). Please wait about a minute "
        + "and retry parsing.";
    public static final String AI_OUT_OF_CREDITS =
        "Parsing couldn't run because the AI account is out of credits. Please contact the "
        + "administrator.";
    public static final String AI_MISCONFIGURED =
        "The AI service isn't configured correctly on the server (authentication/credentials "
        + "problem). Retrying won't help — please contact the administrator.";
    public static final String AI_UNAVAILABLE =
        "The AI service is temporarily unavailable (it returned a server error). Please wait "
        + "a few minutes and retry parsing.";
    public static final String AI_UNREACHABLE =
        "We couldn't reach the AI service (network timeout or connection problem). Please retry "
        + "in a few minutes; if it keeps happening, contact the administrator.";

    // --- Fallback ---
    public static final String UNEXPECTED =
        "An unexpected error occurred during parsing. Please contact the administrator.";

    public static String tooManyPages(int pages, int limit) {
        return "This PDF has " + pages + " pages, over the " + limit + "-page limit. "
            + "Please upload a shorter document (split it if needed) and retry.";
    }

    /**
     * Map an {@link app.ai.AiExceptions.ProviderException} HTTP status to a user-facing
     * message. Rate-limit (429) and payment-required (402) arrive as their own exception
     * types and are handled separately, so they aren't covered here.
     *
     * <ul>
     *   <li>401/403/500 → misconfiguration (auth/credentials or missing API key). The
     *       missing-key path throws {@code ProviderException(msg, 500)}; a persistent
     *       upstream 500 also warrants admin attention.</li>
     *   <li>0/408/425 → transport failure, timeout, or DNS — couldn't reach the service.</li>
     *   <li>≥501 (502/503/504…) → upstream temporarily unavailable.</li>
     *   <li>anything else → treated as temporarily unavailable.</li>
     * </ul>
     */
    public static String forProviderStatus(int status) {
        if (status == 401 || status == 403 || status == 500) return AI_MISCONFIGURED;
        if (status == 0 || status == 408 || status == 425) return AI_UNREACHABLE;
        return AI_UNAVAILABLE;
    }
}

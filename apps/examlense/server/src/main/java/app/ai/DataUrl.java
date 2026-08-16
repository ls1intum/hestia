package app.ai;

/**
 * Parsed {@code data:<mime>;base64,<data>} URL. Shared by the provider clients
 * that need to unpack the data URLs {@code ParseExamService} encodes page
 * images and PDFs into.
 */
record DataUrl(String mediaType, String data) {

    static DataUrl parse(String url, String fallbackMediaType) {
        if (url != null && url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma > 0) {
                String meta = url.substring("data:".length(), comma); // e.g. "image/png;base64"
                int semi = meta.indexOf(';');
                String mediaType = semi >= 0 ? meta.substring(0, semi) : meta;
                return new DataUrl(mediaType.isBlank() ? fallbackMediaType : mediaType, url.substring(comma + 1));
            }
        }
        return new DataUrl(fallbackMediaType, url == null ? "" : url);
    }
}

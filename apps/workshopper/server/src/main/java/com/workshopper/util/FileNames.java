package com.workshopper.util;

/**
 * Download file names made from a session or course title as it is shown (e.g. "Intro to ML - Slides.pptx"),
 * with only the characters file systems forbid removed. Mirrors {@code safeFileName} in client/src/lib/api.ts.
 */
public final class FileNames {

    private static final int MAX_LENGTH = 100;

    private FileNames() {}

    public static String safe(String title, String fallback) {
        String clean = (title == null ? "" : title)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("^\\.+|\\.+$", "");
        if (clean.length() > MAX_LENGTH) clean = clean.substring(0, MAX_LENGTH);
        clean = clean.trim();
        return clean.isEmpty() ? fallback : clean;
    }

    public static String slides(String title) { return safe(title, "Workshop") + " - Slides.pptx"; }

    public static String timetable(String title) { return safe(title, "Workshop") + " - Timetable.pdf"; }

    public static String course(String title) { return safe(title, "Course") + ".zip"; }
}

package de.tum.cit.hestia.learninggoalhub.flow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds tiny synthetic text PDFs in memory, so no fixture file is committed. */
final class TestPdfs {

    private TestPdfs() {
    }

    /** Two pages whose gerund lines the fake model turns into skills; the headings stay text. */
    static final List<List<String>> SORTING_LECTURE = List.of(
            List.of("Week 1",
                    "Implementing merge sort on an array of integers.",
                    "Implementing insertion sort on a linked list."),
            List.of("Week 1, continued",
                    "Comparing the running time of insertion sort and merge sort.",
                    "Analysing the stability of a sorting algorithm."));

    static final List<List<String>> SEARCHING_LECTURE = List.of(
            List.of("Week 2",
                    "Applying binary search to a sorted array.",
                    "Applying linear search to an unsorted list.",
                    "Comparing binary search with linear search.",
                    "Analysing the worst case of binary search."));

    /** One page per entry of {@code pages}, one Helvetica line per string. */
    static byte[] textPdf(List<List<String>> pages) {
        List<String> objects = new ArrayList<>();
        objects.add(null); // object ids start at 1
        List<Integer> pageIds = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            pageIds.add(4 + i * 2);
        }
        StringBuilder kids = new StringBuilder();
        pageIds.forEach(id -> kids.append(id).append(" 0 R "));
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int i = 0; i < pages.size(); i++) {
            StringBuilder stream = new StringBuilder("BT /F1 14 Tf 16 TL 50 780 Td\n");
            for (String line : pages.get(i)) {
                String escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
                stream.append('(').append(escaped).append(") Tj T*\n");
            }
            stream.append("ET");
            int pageId = pageIds.get(i);
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                    + "/Resources << /Font << /F1 3 0 R >> >> /Contents " + (pageId + 1) + " 0 R >>");
            objects.add("<< /Length " + stream.toString().getBytes(StandardCharsets.ISO_8859_1).length
                    + " >>\nstream\n" + stream + "\nendstream");
        }

        StringBuilder out = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.size()];
        for (int id = 1; id < objects.size(); id++) {
            offsets[id] = out.length();
            out.append(id).append(" 0 obj\n").append(objects.get(id)).append("\nendobj\n");
        }
        int xref = out.length();
        out.append("xref\n0 ").append(objects.size()).append("\n0000000000 65535 f \n");
        for (int id = 1; id < objects.size(); id++) {
            out.append(String.format("%010d 00000 n \n", offsets[id]));
        }
        out.append("trailer\n<< /Size ").append(objects.size()).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}

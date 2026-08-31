package de.tum.cit.hestia.learninggoalhub.document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared, deterministic course-material order with numeric-aware document names. */
public final class DocumentOrder {

    private DocumentOrder() {
    }

    public static Comparator<Document> comparator() {
        return (left, right) -> {
            List<BigInteger> leftNumbers = numbers(visibleName(left));
            List<BigInteger> rightNumbers = numbers(visibleName(right));
            if (!leftNumbers.isEmpty() && !rightNumbers.isEmpty()) {
                int bySequence = compareNumberSequences(leftNumbers, rightNumbers);
                if (bySequence != 0) {
                    return bySequence;
                }
                return Comparator.nullsLast(Long::compareTo).compare(left.getId(), right.getId());
            }
            int byId = Comparator.nullsLast(Long::compareTo).compare(left.getId(), right.getId());
            if (byId != 0) {
                return byId;
            }
            int byVisibleName = compareNaturally(visibleName(left), visibleName(right));
            return byVisibleName != 0
                    ? byVisibleName
                    : compareNaturally(left.getFilename(), right.getFilename());
        };
    }

    private static List<BigInteger> numbers(String value) {
        String normalized = normalized(value);
        List<BigInteger> result = new ArrayList<>();
        for (int start = 0; start < normalized.length();) {
            if (!Character.isDigit(normalized.charAt(start))) {
                start++;
                continue;
            }
            int end = runEnd(normalized, start, true);
            result.add(new BigInteger(normalized.substring(start, end)));
            start = end;
        }
        return result;
    }

    private static int compareNumberSequences(List<BigInteger> left, List<BigInteger> right) {
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    static int compareNaturally(String left, String right) {
        String a = normalized(left);
        String b = normalized(right);
        int ai = 0;
        int bi = 0;
        while (ai < a.length() && bi < b.length()) {
            boolean aDigit = Character.isDigit(a.charAt(ai));
            boolean bDigit = Character.isDigit(b.charAt(bi));
            int aEnd = runEnd(a, ai, aDigit);
            int bEnd = runEnd(b, bi, bDigit);
            String aPart = a.substring(ai, aEnd);
            String bPart = b.substring(bi, bEnd);
            int comparison;
            if (aDigit && bDigit) {
                comparison = new BigInteger(aPart).compareTo(new BigInteger(bPart));
                if (comparison == 0) {
                    comparison = Integer.compare(aPart.length(), bPart.length());
                }
            } else {
                comparison = aPart.compareTo(bPart);
            }
            if (comparison != 0) {
                return comparison;
            }
            ai = aEnd;
            bi = bEnd;
        }
        return Integer.compare(a.length(), b.length());
    }

    private static int runEnd(String value, int start, boolean digits) {
        int end = start + 1;
        while (end < value.length() && Character.isDigit(value.charAt(end)) == digits) {
            end++;
        }
        return end;
    }

    private static String visibleName(Document document) {
        return document.getDisplayName() == null || document.getDisplayName().isBlank()
                ? document.getFilename()
                : document.getDisplayName();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}

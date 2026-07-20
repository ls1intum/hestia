package de.tum.cit.hestia.learninggoalhub.hierarchy;

/**
 * Flattened module → session → exercise labels for a single {@link HierarchyNode}.
 *
 * <p>Walking the parent chain of a leaf node yields at most one label per level; levels that are
 * not present in the chain are {@code null}. Shared by the CSV export and the JSON API so both
 * derive the hierarchy path the same way.
 */
public record HierarchyPath(String module, String session, String exercise, Long sessionId) {

    public static HierarchyPath from(HierarchyNode node) {
        String module = null;
        String session = null;
        String exercise = null;
        Long sessionId = null;
        for (HierarchyNode cur = node; cur != null; cur = cur.getParent()) {
            switch (cur.getLevel()) {
                case MODULE -> module = cur.getLabel();
                case SESSION -> {
                    session = cur.getLabel();
                    sessionId = cur.getId();
                }
                case EXERCISE -> exercise = cur.getLabel();
            }
        }
        return new HierarchyPath(module, session, exercise, sessionId);
    }
}

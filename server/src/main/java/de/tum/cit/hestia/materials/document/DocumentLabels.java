package de.tum.cit.hestia.materials.document;

import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.spi.AlignmentTargetLabels;
import de.tum.cit.hestia.materials.goal.PlacedGoals;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Names documents in the review list of {@code core}: their display name, else the file name. */
@Component
class DocumentLabels implements AlignmentTargetLabels {

    private final DocumentRepository documents;
    private final CourseAccess access;

    DocumentLabels(DocumentRepository documents, CourseAccess access) {
        this.documents = documents;
        this.access = access;
    }

    @Override
    public DomainModule module() {
        return DomainModule.MATERIALS;
    }

    @Override
    public Map<String, String> labels(String targetType, Collection<String> targetIds) {
        if (!PlacedGoals.DOCUMENT.equals(targetType)) {
            return Map.of();
        }
        List<Long> ids = targetIds.stream().map(DocumentLabels::parse).filter(java.util.Objects::nonNull).toList();
        Map<UUID, Boolean> readable = new HashMap<>();
        Map<String, String> out = new HashMap<>();
        for (Document d : documents.findAllById(ids)) {
            if (readable.computeIfAbsent(d.getCourseId(), access::canRead)) {
                String name = d.getDisplayName();
                out.put(d.getId().toString(), name == null || name.isBlank() ? d.getFilename() : name);
            }
        }
        return out;
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

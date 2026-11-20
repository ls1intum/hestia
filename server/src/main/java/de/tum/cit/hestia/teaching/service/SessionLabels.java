package de.tum.cit.hestia.teaching.service;

import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.spi.AlignmentTargetLabels;
import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import de.tum.cit.hestia.teaching.repository.WorkshopSessionRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Names sessions by their title in the review list of {@code core}, for sessions the user can see. */
@Component
class SessionLabels implements AlignmentTargetLabels {

    private final WorkshopSessionRepository sessions;
    private final CourseApi courses;

    SessionLabels(WorkshopSessionRepository sessions, CourseApi courses) {
        this.sessions = sessions;
        this.courses = courses;
    }

    @Override
    public DomainModule module() {
        return DomainModule.TEACHING;
    }

    @Override
    public Map<String, String> labels(String targetType, Collection<String> targetIds) {
        Map<String, String> labels = new HashMap<>();
        if (!SessionGoals.LINK_TYPE.equals(targetType) || targetIds.isEmpty()) return labels;
        Set<UUID> readable = Set.copyOf(courses.idsMine(CourseRole.VIEWER));
        for (WorkshopSessionEntity session : sessions.findAllById(targetIds)) {
            if (!readable.contains(session.getCourseId())) continue;
            labels.put(session.getId(), session.getTitle() != null ? session.getTitle() : "Workshop Session");
        }
        return labels;
    }
}

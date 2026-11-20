package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.DomainModule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered repository of the goal tables (current organisation only). */
interface GoalRepository extends JpaRepository<CoreGoal, UUID> {

    List<CoreGoal> findByCourseIdAndRemovedAtIsNullOrderByCreatedAtAscIdAsc(UUID courseId);
}

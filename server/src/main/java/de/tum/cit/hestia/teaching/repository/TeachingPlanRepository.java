package de.tum.cit.hestia.teaching.repository;

import de.tum.cit.hestia.teaching.model.TeachingPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TeachingPlanRepository extends JpaRepository<TeachingPlanEntity, UUID> {
}

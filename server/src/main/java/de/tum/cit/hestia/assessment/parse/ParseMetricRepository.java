package de.tum.cit.hestia.assessment.parse;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParseMetricRepository extends JpaRepository<ParseMetric, UUID> {
}

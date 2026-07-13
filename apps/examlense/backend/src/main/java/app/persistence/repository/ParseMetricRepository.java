package app.persistence.repository;

import app.persistence.entity.ParseMetric;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParseMetricRepository extends JpaRepository<ParseMetric, UUID> {
}

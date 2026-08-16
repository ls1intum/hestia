package app.parse;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParseMetricRepository extends JpaRepository<ParseMetric, UUID> {
}

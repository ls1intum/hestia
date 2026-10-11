package app.user;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, UUID> {

    @Query("select count(u) from LlmUsage u where u.userId = :userId and u.kind = :kind and u.createdAt > :since")
    long countSince(
        @Param("userId") UUID userId,
        @Param("kind") String kind,
        @Param("since") OffsetDateTime since);

    /**
     * Usage across every account. Backs the instance-wide ceiling, which is the
     * only limit that account churn and header spoofing cannot get around.
     */
    @Query("select count(u) from LlmUsage u where u.kind = :kind and u.createdAt > :since")
    long countAllSince(@Param("kind") String kind, @Param("since") OffsetDateTime since);
}

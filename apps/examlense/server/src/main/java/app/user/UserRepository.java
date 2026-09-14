package app.user;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByExternalId(String externalId);

    /**
     * How many accounts this IP has created recently. Counted from the table rather
     * than held in memory so a restart cannot reset the cap — the whole point is to
     * stop someone farming fresh LLM quota by registering repeatedly.
     */
    @Query("select count(u) from User u where u.createdIpHash = :ipHash and u.createdAt > :since")
    long countRegistrationsFrom(@Param("ipHash") String ipHash, @Param("since") OffsetDateTime since);

    /**
     * Touch-only update of the last-seen stamp, run on each principal-cache miss.
     * Column-scoped rather than a {@code save()} of the whole entity: the row is
     * read outside a transaction and would be merged back detached, which would
     * write every field and could clobber a concurrent {@code is_admin} change.
     */
    @Modifying
    @Transactional
    @Query("update User u set u.lastSeenAt = current_timestamp where u.id = :id")
    int touchLastSeen(@Param("id") UUID id);
}

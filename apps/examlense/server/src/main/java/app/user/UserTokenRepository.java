package app.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {

    Optional<UserToken> findByTokenHash(String tokenHash);

    List<UserToken> findByUserId(UUID userId);

    /**
     * Whether this user still holds a usable credential. Shown in the admin roster,
     * where it distinguishes an active account from one whose access was revoked.
     */
    @Query("select count(t) > 0 from UserToken t where t.userId = :userId "
        + "and t.revokedAt is null and (t.expiresAt is null or t.expiresAt > current_timestamp)")
    boolean hasActiveToken(@Param("userId") UUID userId);

    /**
     * Touch-only update of the last-used stamp. Its own transaction so it commits
     * independently of whatever request triggered it, and column-scoped so it
     * cannot race the token's other fields.
     */
    @Modifying
    @Transactional
    @Query("update UserToken t set t.lastUsedAt = current_timestamp where t.id = :id")
    int touch(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("update UserToken t set t.revokedAt = current_timestamp where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId);
}

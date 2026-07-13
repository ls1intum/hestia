package app.persistence;

import java.util.UUID;

/**
 * Single-user placeholder owner id.
 *
 * <p>Real authentication / multi-user support is deferred to a later phase. Until then every row
 * that carries an {@code owner_id} / {@code user_id} is stamped with this fixed id so the
 * owner-scoped data model stays intact and can be back-filled once real users exist.
 */
public final class DefaultUser {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private DefaultUser() {}
}

package app.shared;

import java.util.UUID;

/**
 * The legacy single-user owner id, now a real row in {@code users} (seeded by
 * migration {@code V12}).
 *
 * <p>Every exam created before per-user auth existed carries this id, which is
 * why the row has to keep existing: it is what the {@code exams.owner_id}
 * foreign key resolves to for that historical data. The shared bootstrap token
 * ({@code app.auth.token}) also authenticates as this user, which is how those
 * exams stay reachable until they are reassigned.
 *
 * <p>Retire it by reassigning those exams to a real account and clearing
 * {@code API_AUTH_TOKEN}.
 */
public final class DefaultUser {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private DefaultUser() {}
}

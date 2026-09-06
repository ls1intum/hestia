package app.ai;

/**
 * Mirror of supabase/functions/_shared/solver-strategies.ts. Includes a
 * provider hint so the factory knows which transport to build.
 */
public record SolverStrategy(
    String id,
    String label,
    String description,
    String providerModel,
    ProviderKind providerKind,
    boolean supportsVision
) {
    /**
     * Whether figure images may be attached to this model's requests.
     *
     * <p>The solver model is pinned when the exam is created and never changes, so
     * an exam created against a text-only model can still be re-solved long after
     * the catalog moved on. Sending it images would fail the whole solve, hence an
     * explicit flag rather than an assumption that every model can see.
     */
    public boolean supportsVision() {
        return supportsVision;
    }
}

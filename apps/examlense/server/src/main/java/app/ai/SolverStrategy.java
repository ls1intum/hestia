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
    ProviderKind providerKind
) {}

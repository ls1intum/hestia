package app.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Insertion-ordered strategy registry shared by {@link ParserStrategies} and
 * {@link SolverStrategies}. Distinguishes:
 *   - active strategies ({@link #register}) — shown in the model catalog;
 *   - aliases ({@link #alias}) — legacy ids resolving to an active strategy;
 *   - legacy strategies ({@link #legacy}) — retired models kept resolvable for
 *     rows that still reference them, but hidden from the catalog.
 */
final class StrategyRegistry<T> {

    private static final Logger log = LoggerFactory.getLogger(StrategyRegistry.class);

    private final Map<String, T> active = new LinkedHashMap<>();
    private final Map<String, T> hidden = new LinkedHashMap<>();
    private final Function<T, String> idOf;
    private final String defaultId;

    StrategyRegistry(Function<T, String> idOf, String defaultId) {
        this.idOf = idOf;
        this.defaultId = defaultId;
    }

    void register(T strategy) {
        active.put(idOf.apply(strategy), strategy);
    }

    /** Map a legacy id onto an existing (usually active) strategy. */
    void alias(String legacyId, T target) {
        hidden.put(legacyId, target);
    }

    /** Keep a retired strategy resolvable without listing it in the catalog. */
    void legacy(T strategy) {
        hidden.put(idOf.apply(strategy), strategy);
    }

    List<T> all() {
        return List.copyOf(active.values());
    }

    /** Resolve an id, falling back (with a warning) to the default strategy. */
    T resolve(String id) {
        if (id != null) {
            T s = active.get(id);
            if (s == null) s = hidden.get(id);
            if (s != null) return s;
            log.warn("Unknown strategy id '{}' — falling back to default '{}'", id, defaultId);
        }
        return active.get(defaultId);
    }

    Optional<T> find(String id) {
        T s = active.get(id);
        return s != null ? Optional.of(s) : Optional.ofNullable(hidden.get(id));
    }
}

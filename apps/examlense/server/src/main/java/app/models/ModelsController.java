package app.models;

import app.ai.ParserStrategies;
import app.ai.SolverStrategies;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public-shape endpoints replacing the (planned) list-parser-models and
 * list-solver-models edge functions.
 *
 * Auth: required (any authenticated Supabase user). The lists themselves are
 * not user-scoped, but we keep the boundary authenticated so anonymous
 * callers can't enumerate the model catalog.
 */
@RestController
@RequestMapping("/api")
public class ModelsController {

    public record ModelSummary(String id, String label, String description) {}

    public record ModelListResponse(List<ModelSummary> models, String defaultId) {}

    @GetMapping("/parser-models")
    public ModelListResponse parserModels() {
        List<ModelSummary> models = ParserStrategies.all().stream()
            .map(s -> new ModelSummary(s.id(), s.label(), s.description()))
            .toList();
        return new ModelListResponse(models, ParserStrategies.DEFAULT_ID);
    }

    @GetMapping("/solver-models")
    public ModelListResponse solverModels() {
        List<ModelSummary> models = SolverStrategies.all().stream()
            .map(s -> new ModelSummary(s.id(), s.label(), s.description()))
            .toList();
        return new ModelListResponse(models, SolverStrategies.DEFAULT_ID);
    }
}

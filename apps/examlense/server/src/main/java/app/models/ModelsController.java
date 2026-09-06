package app.models;

import app.ai.ParserStrategies;
import app.ai.SolverStrategies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The parser/solver model catalog the client mirrors in
 * {@code client/src/lib/exam/llm-models.ts}.
 *
 * Auth: required. The lists themselves are not user-scoped, but we keep the
 * boundary authenticated so anonymous callers can't enumerate the catalog.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Models", description = "Catalog of selectable AI parser and solver models.")
public class ModelsController {

    public record ModelSummary(String id, String label, String description) {}

    public record ModelListResponse(List<ModelSummary> models, String defaultId) {}

    @Operation(
        summary = "List PDF parser models",
        description = "Models available for exam PDF parsing, plus the id used when the caller doesn't pick one.")
    @GetMapping("/parser-models")
    public ModelListResponse parserModels() {
        List<ModelSummary> models = ParserStrategies.all().stream()
            .map(s -> new ModelSummary(s.id(), s.label(), s.description()))
            .toList();
        return new ModelListResponse(models, ParserStrategies.DEFAULT_ID);
    }

    @Operation(
        summary = "List solver models",
        description = "Models available for answering tasks. The choice is made at exam creation and locked for the run.")
    @GetMapping("/solver-models")
    public ModelListResponse solverModels() {
        List<ModelSummary> models = SolverStrategies.all().stream()
            .map(s -> new ModelSummary(s.id(), s.label(), s.description()))
            .toList();
        return new ModelListResponse(models, SolverStrategies.DEFAULT_ID);
    }
}

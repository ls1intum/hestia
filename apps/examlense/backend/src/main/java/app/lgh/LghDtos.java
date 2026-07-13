package app.lgh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Hand-written mirrors of the LearningGoalHub API contract (see
 * apps/learninggoalhub/docs/api.md). Only the fields we consume are declared;
 * unknown JSON properties are ignored so LGH can add fields freely.
 */
public final class LghDtos {

    private LghDtos() {}

    /** One ordered exam block for goal generation: shared context or a task. */
    public record ExamBlock(String blockId, String blockType, String taskType, String description) {}

    public record GenerateExamGoalsRequest(List<ExamBlock> blocks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExamTaskGoals(String blockId, List<LearningGoal> goals) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LearningGoal(long id, String text, String kind, String status,
                               String bloomLevel, String soloLevel) {}

    /** Mirrors LGH's CourseSummaryResponse. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Course(long id, String name) {}

    /** Spring PagedModel shape used by LGH's paginated endpoints. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paged<T>(List<T> content, PageMeta page) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageMeta(int size, int number, long totalElements, int totalPages) {}
}

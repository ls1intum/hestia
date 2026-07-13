package app.api;

import app.persistence.entity.Exam;
import app.persistence.entity.ParseSurvey;
import app.persistence.repository.ParseSurveyRepository;
import app.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ParseSurveyController {

    public record SurveyRequest(@NotBlank String exam_id, Integer speed,
                                Integer content_correctness, Integer structure) {}

    private final ParseSurveyRepository surveyRepository;
    private final Access access;

    public ParseSurveyController(ParseSurveyRepository surveyRepository, Access access) {
        this.surveyRepository = surveyRepository;
        this.access = access;
    }

    @PostMapping("/parse-survey")
    public Map<String, Object> create(@Valid @RequestBody SurveyRequest req, @CurrentUser String userId) {
        // Ownership check like every other exam-scoped endpoint; also gives us
        // the exam row for the parser-model snapshot below.
        Exam exam = access.requireExam(Access.id(req.exam_id()), userId);
        ParseSurvey s = new ParseSurvey();
        s.setExamId(exam.getId());
        s.setUserId(UUID.fromString(userId));
        s.setSpeed(req.speed() == null ? null : req.speed().shortValue());
        s.setContentCorrectness(req.content_correctness() == null ? null : req.content_correctness().shortValue());
        s.setStructure(req.structure() == null ? null : req.structure().shortValue());
        // Snapshot the parser model onto the survey row so the by-model rollup stays
        // attributable even after the exam is deleted (see V2 migration).
        s.setParserModel(exam.getParserModel());
        surveyRepository.save(s);
        return Map.of("ok", true, "id", s.getId());
    }
}

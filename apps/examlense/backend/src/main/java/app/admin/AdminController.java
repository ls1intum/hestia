package app.admin;

import app.parse.SurveyDto;

import app.parse.ParseSurveyRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal admin dashboard data — replaces the {@code admin-survey} edge
 * function. Authenticated (static token); a real admin role can be layered on
 * once auth exists.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ParseSurveyRepository surveyRepository;

    public AdminController(ParseSurveyRepository surveyRepository) {
        this.surveyRepository = surveyRepository;
    }

    @GetMapping("/survey")
    public List<SurveyDto> survey() {
        return surveyRepository.findAllByOrderByCreatedAtDesc().stream().map(SurveyDto::from).toList();
    }

    /** Parsing-quality survey scores grouped by parser model — which model does the job best. */
    @GetMapping("/survey-by-model")
    public List<SurveyModelDto> surveyByModel() {
        return surveyRepository.aggregateByModel().stream().map(SurveyModelDto::from).toList();
    }
}

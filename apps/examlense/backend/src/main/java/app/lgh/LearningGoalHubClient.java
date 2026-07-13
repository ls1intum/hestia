package app.lgh;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP client for the LearningGoalHub service (`learninggoalhub.base-url`,
 * env {@code LGH_BASE_URL}). No auth — LGH runs inside the trusted LRZ setup.
 *
 * Two clients with different read timeouts: reads/deletes are fast, but goal
 * generation runs one LLM call per task block on LGH's side and can take
 * minutes for a large section.
 */
@Service
public class LearningGoalHubClient {

    private static final Logger log = LoggerFactory.getLogger(LearningGoalHubClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration GENERATION_READ_TIMEOUT = Duration.ofMinutes(5);
    private static final int PAGE_SIZE = 100;

    private final RestClient readClient;
    private final RestClient generationClient;

    public LearningGoalHubClient(@Value("${learninggoalhub.base-url}") String baseUrl) {
        this.readClient = buildClient(baseUrl, READ_TIMEOUT);
        this.generationClient = buildClient(baseUrl, GENERATION_READ_TIMEOUT);
    }

    private static RestClient buildClient(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder()
            .baseUrl(baseUrl.replaceAll("/$", ""))
            .requestFactory(factory)
            .build();
    }

    /**
     * Derive and persist learning goals for an exam's task blocks. Synchronous
     * on LGH's side (one LLM call per task) — call from a background thread.
     * NOTE: LGH does not dedup; posting the same blocks twice creates the
     * goals twice. Callers must clean up prior goals before regenerating.
     */
    public List<LghDtos.ExamTaskGoals> generateExamGoals(long courseId, List<LghDtos.ExamBlock> blocks) {
        List<LghDtos.ExamTaskGoals> out = generationClient.post()
            .uri("/api/courses/{courseId}/exam-tasks/learning-goals", courseId)
            .body(new LghDtos.GenerateExamGoalsRequest(blocks))
            .retrieve()
            .body(new ParameterizedTypeReference<List<LghDtos.ExamTaskGoals>>() {});
        return out == null ? List.of() : out;
    }

    public List<LghDtos.Course> listCourses() {
        return fetchAllPages("/api/courses",
            new ParameterizedTypeReference<LghDtos.Paged<LghDtos.Course>>() {});
    }

    /** All goals of a course (any review status — exam-derived goals are PENDING). */
    public List<LghDtos.LearningGoal> listGoals(long courseId) {
        return fetchAllPages("/api/courses/" + courseId + "/learning-goals",
            new ParameterizedTypeReference<LghDtos.Paged<LghDtos.LearningGoal>>() {});
    }

    /** Delete a goal we generated. A 404 means it is already gone — not an error. */
    public void deleteGoal(long courseId, long goalId) {
        try {
            readClient.delete()
                .uri("/api/courses/{courseId}/learning-goals/{goalId}", courseId, goalId)
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("LGH goal {} already deleted", goalId);
        }
    }

    private <T> List<T> fetchAllPages(String path, ParameterizedTypeReference<LghDtos.Paged<T>> type) {
        List<T> all = new ArrayList<>();
        int totalPages = 1;
        for (int page = 0; page < totalPages; page++) {
            final int p = page;
            LghDtos.Paged<T> res = readClient.get()
                .uri(uri -> uri.path(path).queryParam("page", p).queryParam("size", PAGE_SIZE).build())
                .retrieve()
                .body(type);
            if (res == null || res.content() == null) break;
            all.addAll(res.content());
            if (res.page() == null) break;
            totalPages = res.page().totalPages();
        }
        return all;
    }
}

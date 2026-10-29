package com.workshopper.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads from LearningGoalHub for the goal import. The calls go through this server rather than the
 * browser because LGH authenticates its consumers with a service key ({@code X-Api-Key}), which must
 * not ship in the client bundle. Answers are passed on unchanged.
 */
@Service
public class LearningGoalHubClient {

    private static final Logger log = LoggerFactory.getLogger(LearningGoalHubClient.class);

    private final RestClient client;

    public LearningGoalHubClient(@Value("${learninggoalhub.base-url}") String baseUrl,
                                 @Value("${learninggoalhub.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/$", ""))
                .requestFactory(factory);
        if (!apiKey.isBlank()) {
            builder.defaultHeader("X-Api-Key", apiKey.strip());
        }
        this.client = builder.build();
    }

    /** One page of LGH's course list, as LGH returns it ({@code content} plus page metadata). */
    public JsonNode courses(int page, int size) {
        return get("/api/courses?page={page}&size={size}", page, size);
    }

    /** A course's learning goals grouped by session, as LGH returns them. */
    public JsonNode goalsBySession(long courseId) {
        return get("/api/courses/{courseId}/learning-goals/by-session", courseId);
    }

    private JsonNode get(String uri, Object... variables) {
        try {
            return client.get().uri(uri, variables).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found in LearningGoalHub.");
        } catch (RestClientException e) {
            log.warn("LearningGoalHub request {} failed: {}", uri, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LearningGoalHub is not reachable right now.");
        }
    }
}

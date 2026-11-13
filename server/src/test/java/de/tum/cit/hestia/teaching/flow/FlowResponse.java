package de.tum.cit.hestia.teaching.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Status and body of an HTTP answer; the body is parsed on demand as generic JSON. */
record FlowResponse(int status, String body) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    JsonNode json() {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Not JSON (status " + status + "): " + body, e);
        }
    }
}

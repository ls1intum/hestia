package app.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for every outbound AI call, so no flow test ever needs a real
 * key or network. Started once per JVM on a random port; the tests wire the
 * server's URL into the app via configuration properties (see
 * {@link AbstractHttpFlowTest}).
 *
 * <p>Two things are provider-specific and live ONLY in this class, so replacing
 * the provider clients by one gateway means editing this file and nothing else:
 * <ol>
 *   <li>{@link #wrap}: how the canned tool arguments are packaged into a provider
 *       response, chosen from the request path;</li>
 *   <li>{@link #kindOf}: how "this is a parse call" vs "this is a solve call" is
 *       recognised, from the tool name in the request body.</li>
 * </ol>
 * The canned content itself ({@link #examPayload}, {@link #answersPayload}) is
 * provider-independent.
 */
final class FakeModelServer {

    /** What the model "reads" the synthetic exam as. Tests assert against these. */
    static final String EXAM_TITLE = "Synthetic Flow Exam";
    static final String EXAM_COURSE = "Flow Testing 101";
    static final String SOLVED_TEXT_ANSWER = "Canned model answer: a status code summarises the result of a request.";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TASK_HEADER =
        Pattern.compile("\\[t\\d+\\] \\((single_choice|multiple_choice|text), id=([0-9a-fA-F-]{36})");
    private static final Pattern OPTION_ID = Pattern.compile("[a-z]\\) id=([0-9a-fA-F-]{36})");

    private final HttpServer server;
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();

    FakeModelServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start fake model server", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-model-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Base URL; each provider gets its own sub-path so a stray call is attributable. */
    String url(String subPath) {
        return "http://localhost:" + server.getAddress().getPort() + subPath;
    }

    /** Paths of every request that reached the fake (proves the app really used it). */
    List<String> requestPaths() {
        return List.copyOf(requestPaths);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requestPaths.add(path);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Map<String, Object> args;
        switch (kindOf(body)) {
            case PARSE -> args = examPayload();
            case SOLVE -> args = answersPayload(body);
            default -> {
                reply(ex, 400, "{\"error\":\"fake model server: unrecognised call\"}");
                return;
            }
        }
        reply(ex, 200, wrap(path, args));
    }

    // --- provider-specific part (edit here when the provider clients go away) ---

    private enum Kind { PARSE, SOLVE, OTHER }

    private static Kind kindOf(String requestBody) {
        if (requestBody.contains("\"extract_exam\"")) return Kind.PARSE;
        if (requestBody.contains("\"submit_answers\"")) return Kind.SOLVE;
        return Kind.OTHER;
    }

    private static String wrap(String path, Map<String, Object> toolArgs) {
        try {
            Map<String, Object> res = new LinkedHashMap<>();
            if (path.contains("generateContent")) { // Gemini
                res.put("modelVersion", "fake-gemini");
                res.put("candidates", List.of(Map.of("content", Map.of("parts",
                    List.of(Map.of("functionCall", Map.of("name", "tool", "args", toolArgs)))))));
            } else if (path.endsWith("/responses")) { // OpenAI Responses
                res.put("model", "fake-openai");
                res.put("output", List.of(Map.of(
                    "type", "function_call", "name", "tool",
                    "arguments", JSON.writeValueAsString(toolArgs))));
            } else if (path.endsWith("/messages")) { // Anthropic
                res.put("model", "fake-anthropic");
                res.put("content", List.of(Map.of("type", "tool_use", "name", "tool", "input", toolArgs)));
            } else {
                throw new IllegalStateException("fake model server: unknown provider path " + path);
            }
            return JSON.writeValueAsString(res);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- canned content (provider-independent) ---

    private static Map<String, Object> examPayload() {
        Map<String, Object> exam = new LinkedHashMap<>();
        exam.put("detected_language", "en");
        exam.put("title", EXAM_TITLE);
        exam.put("course", EXAM_COURSE);
        exam.put("sections", List.of(Map.of("name", "Part A")));
        exam.put("tasks", List.of(
            Map.of("section", "Part A", "type", "single_choice", "points", 2,
                "prompt", "Which keyword declares a constant in Java?",
                "options", List.of(
                    Map.of("text", "final", "is_correct", true),
                    Map.of("text", "var", "is_correct", false),
                    Map.of("text", "let", "is_correct", false))),
            Map.of("section", "Part A", "type", "multiple_choice", "points", 3,
                "prompt", "Which of these are HTTP methods?",
                "options", List.of(
                    Map.of("text", "GET", "is_correct", true),
                    Map.of("text", "POST", "is_correct", true),
                    Map.of("text", "FETCHALL", "is_correct", false))),
            Map.of("section", "Part A", "type", "text", "points", 4,
                "prompt", "Explain what an HTTP status code is.")));
        return exam;
    }

    /** Answers every task found in the solver prompt: first option for choices, fixed text otherwise. */
    private static Map<String, Object> answersPayload(String requestBody) {
        // The prompt reaches us JSON-escaped inside the body; splitting on the task
        // header keeps each task's option ids together with its own id.
        String[] chunks = requestBody.split("(?=\\[t\\d+\\] \\()");
        List<Map<String, Object>> answers = new ArrayList<>();
        for (String chunk : chunks) {
            Matcher header = TASK_HEADER.matcher(chunk);
            if (!header.find()) continue;
            String type = header.group(1);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("task_id", header.group(2));
            a.put("type", type);
            a.put("reasoning", "canned");
            if ("text".equals(type)) {
                a.put("answer_text", SOLVED_TEXT_ANSWER);
            } else {
                Matcher opt = OPTION_ID.matcher(chunk);
                a.put("selected_option_ids", opt.find() ? List.of(opt.group(1)) : List.of());
            }
            answers.add(a);
        }
        return Map.of("answers", answers);
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}

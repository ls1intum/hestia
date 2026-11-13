package de.tum.cit.hestia.materials.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the OpenAI-compatible model endpoint: the server under test talks to it over
 * HTTP, exactly as it would to the real gateway, and gets fixed answers. No key, no network.
 *
 * <p>It recognises each prompt by the JSON shape the prompt asks for (the same approach as the
 * browser tests' {@code client/e2e/fake-llm.mjs}, which this is a port of) and answers
 * deterministically from the prompt's own numbered lines "[n] text". An unknown prompt gets a 400,
 * which fails the test instead of silently passing.
 */
final class FakeModelServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NUMBERED = Pattern.compile("^\\[(\\d+)] (.*)$", Pattern.MULTILINE);
    private static final Pattern FENCE = Pattern.compile("^---$", Pattern.MULTILINE);
    private static final Pattern GERUND_LINE = Pattern.compile("^[A-Z][a-z]+ing\\b.*");

    private final HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private record Line(int index, String text) {
    }

    FakeModelServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake model server", e);
        }
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "fake-model");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] request = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                int status;
                Object body;
                if (!"POST".equals(exchange.getRequestMethod()) || !path.endsWith("/chat/completions")) {
                    status = 404;
                    body = Map.of("error", Map.of("message", "fake model: no route " + path));
                } else {
                    JsonNode req = JSON.readTree(request);
                    String prompt = promptOf(req);
                    Object[] answer = answer(prompt);
                    if (answer == null) {
                        calls.add("unknown");
                        status = 400;
                        body = Map.of("error", Map.of("message", "fake model: unknown prompt: "
                                + prompt.substring(0, Math.min(400, prompt.length()))));
                    } else {
                        calls.add((String) answer[0]);
                        status = 200;
                        body = completion(req.path("model").asText("fake"), JSON.writeValueAsString(answer[1]));
                    }
                }
                byte[] out = JSON.writeValueAsBytes(body);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
            }
        });
        server.start();
    }

    /** Base URL to give the server as its model endpoint. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Kinds of the model calls answered so far; "unknown" marks a prompt the fake could not place. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    private static String promptOf(JsonNode request) {
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : request.path("messages")) {
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                prompt.append(content.asText());
            } else if (content.isArray()) {
                content.forEach(part -> prompt.append(part.path("text").asText("")).append('\n'));
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private static Map<String, Object> completion(String model, String content) {
        Map<String, Object> message = Map.of("role", "assistant", "content", content);
        Map<String, Object> choice = Map.of("index", 0, "message", message, "finish_reason", "stop");
        return Map.of(
                "id", "fake-completion",
                "object", "chat.completion",
                "created", System.currentTimeMillis() / 1000,
                "model", model,
                "choices", List.of(choice),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
    }

    /** {kind, reply} for a prompt, or null when it is not one this fake knows. */
    private static Object[] answer(String prompt) {
        if (prompt.contains("sourceStartLine")) {
            return new Object[] {"extraction", extraction(prompt)};
        }
        if (prompt.contains("subCapabilities")) {
            return new Object[] {"structure", structure(prompt)};
        }
        if (prompt.contains("\"labels\"") && prompt.contains("shortLabel")) {
            return new Object[] {"short-labels", shortLabels(prompt)};
        }
        if (prompt.contains("\"assignments\"")) {
            return new Object[] {"assignment", assignment(prompt)};
        }
        if (prompt.contains("\"capabilities\"")) {
            return new Object[] {"naming", naming(prompt)};
        }
        if (prompt.contains("educational taxonomies")) {
            return new Object[] {"taxonomy", taxonomy(prompt)};
        }
        return null;
    }

    private static final Pattern GOAL_LINE = Pattern.compile("(?m)^(\\d+)\\. ");

    /** Bloom/SOLO batch classification: one fixed level pair per numbered goal ("1. ..."). */
    private static List<Map<String, Object>> taxonomy(String prompt) {
        List<Map<String, Object>> items = new ArrayList<>();
        Matcher m = GOAL_LINE.matcher(prompt);
        while (m.find()) {
            items.add(Map.of("index", Integer.parseInt(m.group(1)), "bloom", "APPLY", "solo", "RELATIONAL"));
        }
        return items;
    }

    private static List<Line> numbered(String text) {
        List<Line> lines = new ArrayList<>();
        Matcher m = NUMBERED.matcher(text);
        while (m.find()) {
            lines.add(new Line(Integer.parseInt(m.group(1)), m.group(2).strip()));
        }
        return lines;
    }

    /** The numbered lines between the last pair of "---" fences, where the tree prompts list items. */
    private static List<Line> fencedItems(String prompt) {
        String[] parts = FENCE.split(prompt, -1);
        return numbered(parts.length >= 3 ? parts[parts.length - 2] : prompt);
    }

    private static String topicOf(String text) {
        return text.toLowerCase().contains("search") ? "Searching algorithms" : "Sorting algorithms";
    }

    private static String stripDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String shortLabel(String text) {
        String[] words = text.split(" ");
        return String.join(" ", Arrays.copyOf(words, Math.min(3, words.length)));
    }

    private static List<Map<String, Object>> extraction(String prompt) {
        List<Map<String, Object>> goals = new ArrayList<>();
        for (Line line : numbered(prompt)) {
            if (!GERUND_LINE.matcher(line.text()).matches()) {
                continue;
            }
            String text = stripDot(line.text());
            Map<String, Object> knowledge = new LinkedHashMap<>();
            knowledge.put("text", "Recalling the terms behind " + Character.toLowerCase(text.charAt(0)) + text.substring(1));
            knowledge.put("shortLabel", "Terms of " + line.text().split(" ")[1]);
            knowledge.put("kind", "IMPLICIT");
            knowledge.put("bloom", "UNDERSTAND");
            knowledge.put("solo", "UNISTRUCTURAL");
            knowledge.put("sourceStartLine", line.index());
            knowledge.put("sourceEndLine", line.index());
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("text", text);
            skill.put("shortLabel", shortLabel(line.text()));
            skill.put("kind", "EXPLICIT");
            skill.put("bloom", "APPLY");
            skill.put("solo", "MULTISTRUCTURAL");
            skill.put("sourceStartLine", line.index());
            skill.put("sourceEndLine", line.index());
            skill.put("knowledge", List.of(knowledge));
            goals.add(skill);
        }
        return goals;
    }

    private static Map<String, Object> naming(String prompt) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        fencedItems(prompt).forEach(item -> topics.add(topicOf(item.text())));
        return Map.of("capabilities", topics.isEmpty() ? List.of("General topic") : List.copyOf(topics));
    }

    /** Each outcome under the label named after its subject; the labels come before the outcome list. */
    private static Map<String, Object> assignment(String prompt) {
        String[] parts = FENCE.split(prompt, -1);
        List<Line> labels = numbered(String.join("\n", Arrays.copyOf(parts, Math.max(0, parts.length - 2))));
        List<List<Integer>> assignments = new ArrayList<>();
        for (Line outcome : fencedItems(prompt)) {
            String wanted = topicOf(outcome.text());
            int label = -1;
            for (Line l : labels) {
                if (l.text().contains(wanted)) {
                    label = l.index();
                    break;
                }
            }
            if (label == -1 && !labels.isEmpty()) {
                label = labels.get(0).index();
            }
            assignments.add(List.of(outcome.index(), label));
        }
        return Map.of("assignments", assignments);
    }

    /** Two skills per topic: the first half of its outcomes and the second. */
    private static Map<String, Object> structure(String prompt) {
        List<Integer> items = fencedItems(prompt).stream().map(Line::index).toList();
        Matcher m = Pattern.compile("^Topic: (.*)$", Pattern.MULTILINE).matcher(prompt);
        String label = m.find() ? m.group(1) : "this topic";
        int half = (items.size() + 1) / 2;
        return Map.of(
                "subCapabilities", List.of(
                        Map.of("name", "Implementing " + label.toLowerCase(), "outcomes", items.subList(0, half)),
                        Map.of("name", "Analysing " + label.toLowerCase(), "outcomes", items.subList(half, items.size()))),
                "direct", List.of());
    }

    private static Map<String, Object> shortLabels(String prompt) {
        List<Map<String, Object>> labels = new ArrayList<>();
        for (Line item : fencedItems(prompt)) {
            labels.add(Map.of("index", item.index(), "shortLabel", shortLabel(item.text())));
        }
        return Map.of("labels", labels);
    }
}

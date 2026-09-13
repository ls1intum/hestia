package de.tum.cit.hestia.learninggoalhub.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Builds the competency tree top-down from a course's extracted skills in three steps.
 *
 * <ol>
 *   <li>Name the course's topics: short noun phrases, one call over every skill, no examples and no
 *       target count.</li>
 *   <li>Assign each skill to one topic, in batches against the fixed topic list. A skill no topic
 *       covers is left out of the tree.</li>
 *   <li>Structure each topic on its own: group its skills into capabilities, each with a written
 *       name, and leave the rest directly under the topic.</li>
 * </ol>
 *
 * <p>The model's answers are normalised rather than validated: an index that is invented or repeated
 * is ignored, a skill the assignment omits is left out like an unmatched one, and a skill the
 * structuring omits stays directly under its topic. Nothing is retried, so a run costs exactly one
 * naming call, one call per assignment batch and one call per topic with at least two skills.
 */
@Service
public class TopicTreeSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(TopicTreeSynthesizer.class);

    static final int ASSIGNMENT_BATCH = 40;
    private static final int PARALLEL_CALLS = 4;
    static final int UNMATCHED = -1;

    static final String NAMING_PROMPT = """
            Name the capabilities this university course develops, based on the extracted learning
            outcomes below.

            Return SHORT labels: two to four words, a noun phrase naming the shared object, method or body of
            technique — not a sentence, not a performance, no leading verb. Write every label in %s.

            - Each label names something that several of the outcomes below jointly build toward.
            - Do not return one label per outcome, and do not paraphrase a single outcome as a label.
            - Do not create catch-all, miscellaneous or residual labels.
            - No target number is prescribed. Return as many labels as the material genuinely distinguishes,
              and no more.
            - Do not assign outcomes to labels, and do not group them. Return the labels only.

            Return only {"capabilities":["...","..."]}.

            Extracted outcomes:
            ---
            %s
            ---
            """;

    static final String ASSIGNMENT_PROMPT = """
            Assign each extracted learning outcome below to the ONE capability label it belongs to.

            The labels and outcomes are fixed. Do not rename, merge, split, add or rewrite them.

            Each label is a short noun phrase naming a shared object, method or body of technique of this course.
            An outcome belongs to a label when the content it teaches is part of that object, method or body of
            technique.

            HOW TO DECIDE

            For each outcome:
            1. Read the complete outcome and identify what it is about: the object, method or body of technique
               whose content a student learns from it.
            2. Choose the label that covers that content.
            3. Return -1 if no label covers it.

            - Ignore the opening verb and the cognitive level. Recalling, understanding, applying or analysing
              the same content belong to the same label.
            - Decide by what the outcome is about, not by a word it shares with a label. A method the outcome
              only uses as a tool, or a subject it only mentions in passing, does not decide the match.
            - When several labels cover the content, choose the most specific one. If still tied, choose the
              lowest label index.
            - Outcomes that describe the same content must receive the same label.
            - Do not use a broad or foundational label as a fallback for outcomes that fit nothing more
              specific. Assign an outcome to a broad label only when it is about that broad subject itself.
            - Judge each outcome on its content. Do not balance label sizes or try to give every label members.

            NO SUITABLE LABEL

            Use -1 when no listed label covers the outcome's content. The label list may be incomplete; an
            honest -1 is better than a forced match. Do not use -1 merely because an outcome covers only one
            part of a label.

            OUTPUT

            Return only structured JSON:
            {"assignments":[[0,3],[1,-1],[2,0]]}

            Each pair is [outcome index, label index].

            - Use the exact indices supplied below.
            - Every supplied outcome index must appear exactly once, in ascending order.
            - Each label index must be one listed below, or -1.
            - Do not return explanations or additional fields.

            Capability labels:
            ---
            %s
            ---

            Extracted outcomes to assign:
            ---
            %s
            ---
            """;

    static final String STRUCTURE_PROMPT = """
            Structure the learning outcomes that a university course teaches under one topic.

            Topic: %s

            The outcomes below have already been assigned to this topic. Organise them into sub-capabilities:
            the parts of the topic that a student learns and is assessed on as one unit.

            SUB-CAPABILITIES

            - A sub-capability is one part of the topic as it is taught: a method together with its
              procedure, properties and variants, a mechanism together with its consequences, or a body of
              related results. Put every outcome that belongs to that part into it.
            - It must hold at least two outcomes and must be narrower than the topic. If the outcomes do not
              divide into such parts, return no sub-capabilities and leave every outcome directly under the
              topic.
            - Group by what the outcomes are about. Do not group by opening verb or cognitive level:
              explaining a method and carrying it out belong together.
            - Do not create catch-all, miscellaneous, basics or residual sub-capabilities.
            - No target number is prescribed. Form as many as the topic genuinely has parts, and no more.

            OUTCOMES WITHOUT A SUB-CAPABILITY

            An outcome that belongs to none of the parts stays directly under the topic. Do not create a
            sub-capability for a single outcome.

            NAMING

            - Name each sub-capability as one sentence of at most twelve words stating what the student can
              do, starting with the action in base form ("Explain ...", "Compute ...", "Compare ..."). Write
              every name in %s.
            - The action must match what the members teach. If the members only explain or recognise
              something, use an explanatory action; do not name a performance such as implementing, computing
              or tuning that no member teaches.
            - The name must cover all its members. Do not reuse or paraphrase the wording of a single member.
            - Do not repeat the topic label as the name.

            OUTPUT

            Return only JSON:
            {"subCapabilities":[{"name":"...","outcomes":[0,2,5]}],"direct":[3]}

            - Every outcome index below appears exactly once: in one sub-capability or in "direct".
            - Do not return explanations or additional fields.

            Outcomes:
            ---
            %s
            ---
            """;

    /** The finished tree, in course-index space. */
    public record Plan(List<PlannedTopic> topics, List<Integer> unmatched) {
        public Plan {
            topics = List.copyOf(topics);
            unmatched = List.copyOf(unmatched);
        }
    }

    /** One topic: its label, its capabilities, and the skills that stay directly beneath it. */
    public record PlannedTopic(String label, List<PlannedCapability> capabilities, List<Integer> direct) {
        public PlannedTopic {
            capabilities = List.copyOf(capabilities);
            direct = List.copyOf(direct);
        }
    }

    /** One capability: a written name over at least two of the topic's skills. */
    public record PlannedCapability(String name, List<Integer> outcomes) {
        public PlannedCapability {
            outcomes = List.copyOf(outcomes);
        }
    }

    // The prompts above are kept verbatim from the runs that validated them, where the tiers were
    // still called capability and sub-capability. Their nouns are an experimental variable — the
    // label count moves with the wording — so the reply keys stay as measured and the records carry
    // the names the tree uses. Renaming a prompt noun is a change to re-measure, not a rename.
    record TopicNames(@JsonProperty("capabilities") List<String> topics) {}

    record Assignments(List<List<Integer>> assignments) {}

    record CapabilityGroup(String name, List<Integer> outcomes) {}

    record TopicStructure(@JsonProperty("subCapabilities") List<CapabilityGroup> capabilities,
                          List<Integer> direct) {}

    private final ChatClient chatClient;
    private final String model;
    private final double temperature;

    public TopicTreeSynthesizer(
            ChatClient.Builder chatClientBuilder,
            @Value("${hestia.extraction.taxonomy-planner-model:openai-gpt-oss-120b}") String model,
            @Value("${hestia.extraction.assignment-temperature:0.0}") double temperature) {
        this.chatClient = chatClientBuilder.build();
        this.model = model;
        this.temperature = temperature;
    }

    /**
     * Plans the tree over {@code outcomes}, the course's extracted skills in lecture order.
     *
     * @throws RuntimeException when a call fails or no topic could be named; nothing is partial.
     */
    public Plan synthesize(List<String> outcomes, String languageName, String modelOverride) {
        if (outcomes == null || outcomes.isEmpty()) {
            return new Plan(List.of(), List.of());
        }
        String effectiveModel = modelOverride == null || modelOverride.isBlank() ? model : modelOverride;
        try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_CALLS)) {
            List<String> topics = normalizeNames(call(
                    NAMING_PROMPT.formatted(languageName, numbered(outcomes, 0, outcomes.size())),
                    effectiveModel, new ParameterizedTypeReference<TopicNames>() {}));
            if (topics.isEmpty()) {
                throw new IllegalStateException("The model named no topics for " + outcomes.size() + " skills.");
            }
            log.info("Topic tree named {} topics for {} skills", topics.size(), outcomes.size());

            String menu = numbered(topics, 0, topics.size());
            List<CompletableFuture<Map<Integer, Integer>>> batches = new ArrayList<>();
            for (int start = 0; start < outcomes.size(); start += ASSIGNMENT_BATCH) {
                int from = start;
                int to = Math.min(start + ASSIGNMENT_BATCH, outcomes.size());
                batches.add(async(executor, () -> normalizeAssignments(call(
                        ASSIGNMENT_PROMPT.formatted(menu, numbered(outcomes, from, to)),
                        effectiveModel, new ParameterizedTypeReference<Assignments>() {}),
                        from, to, topics.size())));
            }
            Map<Integer, Integer> assignment = new LinkedHashMap<>();
            batches.forEach(batch -> assignment.putAll(join(batch)));
            List<List<Integer>> members = membersByTopic(assignment, outcomes.size(), topics.size());

            List<CompletableFuture<TopicStructure>> structures = new ArrayList<>();
            for (int topic = 0; topic < topics.size(); topic++) {
                List<Integer> topicMembers = members.get(topic);
                if (topicMembers.size() < 2) {
                    structures.add(CompletableFuture.completedFuture(new TopicStructure(List.of(), List.of())));
                    continue;
                }
                List<String> texts = topicMembers.stream().map(outcomes::get).toList();
                String label = topics.get(topic);
                structures.add(async(executor, () -> call(
                        STRUCTURE_PROMPT.formatted(label, languageName, numbered(texts, 0, texts.size())),
                        effectiveModel, new ParameterizedTypeReference<TopicStructure>() {})));
            }

            List<PlannedTopic> planned = new ArrayList<>();
            for (int topic = 0; topic < topics.size(); topic++) {
                List<Integer> topicMembers = members.get(topic);
                if (topicMembers.isEmpty()) {
                    continue;
                }
                planned.add(structureTopic(topics.get(topic), topicMembers, join(structures.get(topic))));
            }
            List<Integer> unmatched = unmatched(assignment, outcomes.size());
            log.info("Topic tree assigned {} of {} skills to {} topics with {} capabilities",
                    outcomes.size() - unmatched.size(), outcomes.size(), planned.size(),
                    planned.stream().mapToInt(t -> t.capabilities().size()).sum());
            return new Plan(planned, unmatched);
        }
    }

    /** Trimmed, non-blank labels, first occurrence of each (case-insensitive) kept. */
    static List<String> normalizeNames(TopicNames names) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (names == null || names.topics() == null) {
            return result;
        }
        for (String name : names.topics()) {
            String label = name == null ? "" : name.strip();
            if (!label.isEmpty() && seen.add(label.toLowerCase(Locale.ROOT))) {
                result.add(label);
            }
        }
        return result;
    }

    /**
     * One batch's answer as outcome index -> topic index. Only indices inside {@code [from, to)} are
     * read, the first answer for an outcome wins, and a topic index outside the menu counts as
     * unmatched. Outcomes the answer leaves out are simply absent.
     */
    static Map<Integer, Integer> normalizeAssignments(Assignments answer, int from, int to, int topicCount) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        if (answer == null || answer.assignments() == null) {
            return result;
        }
        for (List<Integer> pair : answer.assignments()) {
            if (pair == null || pair.size() != 2 || pair.get(0) == null || pair.get(1) == null) {
                continue;
            }
            int outcome = pair.get(0);
            int topic = pair.get(1);
            if (outcome < from || outcome >= to || result.containsKey(outcome)) {
                continue;
            }
            result.put(outcome, topic >= 0 && topic < topicCount ? topic : UNMATCHED);
        }
        return result;
    }

    /** Each topic's outcomes in course order. */
    static List<List<Integer>> membersByTopic(Map<Integer, Integer> assignment, int outcomeCount, int topicCount) {
        List<List<Integer>> members = new ArrayList<>();
        for (int topic = 0; topic < topicCount; topic++) {
            members.add(new ArrayList<>());
        }
        for (int outcome = 0; outcome < outcomeCount; outcome++) {
            Integer topic = assignment.get(outcome);
            if (topic != null && topic != UNMATCHED) {
                members.get(topic).add(outcome);
            }
        }
        return members;
    }

    /** Outcomes that ended up under no topic: answered -1 or left out of their batch's answer. */
    static List<Integer> unmatched(Map<Integer, Integer> assignment, int outcomeCount) {
        List<Integer> result = new ArrayList<>();
        for (int outcome = 0; outcome < outcomeCount; outcome++) {
            Integer topic = assignment.get(outcome);
            if (topic == null || topic == UNMATCHED) {
                result.add(outcome);
            }
        }
        return result;
    }

    /**
     * Maps one topic's structuring answer (topic-local indices) back to course indices. A group with a
     * blank name or fewer than two outcomes is dissolved, a single group holding every outcome is the
     * topic itself and is dissolved too, and every outcome not placed in a surviving group stays
     * directly under the topic.
     */
    static PlannedTopic structureTopic(String label, List<Integer> members, TopicStructure answer) {
        int size = members.size();
        Set<Integer> placed = new HashSet<>();
        List<PlannedCapability> capabilities = new ArrayList<>();
        if (answer != null && answer.capabilities() != null) {
            for (CapabilityGroup group : answer.capabilities()) {
                if (group == null || group.outcomes() == null || group.name() == null || group.name().isBlank()) {
                    continue;
                }
                List<Integer> local = group.outcomes().stream()
                        .filter(index -> index != null && index >= 0 && index < size && !placed.contains(index))
                        .distinct()
                        .toList();
                if (local.size() < 2) {
                    continue;
                }
                placed.addAll(local);
                capabilities.add(new PlannedCapability(group.name().strip(),
                        local.stream().sorted().map(members::get).toList()));
            }
        }
        if (capabilities.size() == 1 && capabilities.getFirst().outcomes().size() == size) {
            capabilities.clear();
            placed.clear();
        }
        List<Integer> direct = new ArrayList<>();
        for (int local = 0; local < size; local++) {
            if (!placed.contains(local)) {
                direct.add(members.get(local));
            }
        }
        return new PlannedTopic(label, capabilities, direct);
    }

    private <T> T call(String prompt, String model, ParameterizedTypeReference<T> type) {
        ChatOptions.Builder options = ChatOptions.builder().temperature(temperature);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }
        return chatClient.prompt()
                .options(options.build())
                .user(prompt)
                .call()
                .entity(LenientJson.converter(type));
    }

    private static <T> CompletableFuture<T> async(ExecutorService executor, Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private static String numbered(List<String> items, int from, int to) {
        StringBuilder result = new StringBuilder();
        for (int index = from; index < to; index++) {
            result.append('[').append(index).append("] ").append(items.get(index)).append('\n');
        }
        return result.toString();
    }
}

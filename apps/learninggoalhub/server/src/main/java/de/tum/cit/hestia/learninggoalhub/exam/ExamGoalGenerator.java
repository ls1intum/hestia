package de.tum.cit.hestia.learninggoalhub.exam;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * LLM stage of the exam-goal endpoint: reads ONE exam task (plus the shared context blocks that
 * precede it in the exam) and returns the learning goals the task assesses. Called once per task
 * so each task only sees the context that applies to it; {@link ExamGoalService} orchestrates the
 * per-task calls and persists the results.
 */
@Service
public class ExamGoalGenerator {

    static final String PROMPT_TEMPLATE = """
            You derive the learning goals that ONE exam task assesses.

            A learning goal is a learning OUTCOME: a statement of what a student must be able to do
            to solve the task, phrased the way an instructor writes learning goals — a single concise
            sentence starting with a verb (e.g. "Explain the bias-variance tradeoff", "Apply gradient
            descent to train a linear model").

            Rules:
            - Derive goals ONLY from what the task actually assesses. Do not invent broader course
              goals the task merely touches, and do not restate the task itself.
            - Each goal must be ATOMIC: exactly one verb, one outcome. Split compound outcomes into
              separate goals.
            - Be conservative about the count: most tasks assess a single goal. Return more than one
              only when the task genuinely assesses clearly distinct outcomes, and never more than
              three.
            - Choose the verb by the cognitive action the task demands of the student (recall,
              explain, apply, analyse, evaluate, create) — do not escalate, e.g. a recall question
              must not become an "apply" goal.

            Return the list of goals, each with:
              - text: the learning goal as a single concise sentence, starting with a verb.
            Write the text value in %s. Keep the JSON property name text exactly as written.
            %s
            The exam task (task type: %s):
            ---
            %s
            ---
            """;

    private static final String CONTEXT_SECTION = """

            Shared exam context that applies to this task (background only — derive goals from the
            task, not from the context):
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public ExamGoalGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * @param context         the accumulated CONTEXT blocks preceding the task; blank for none.
     * @param taskType        the consumer's task-type label (e.g. "singleChoice"); null when unknown.
     * @param taskDescription the task statement.
     * @param modelOverride   optional SAIA model id; falls back to the configured default when blank.
     * @return the learning goals the task assesses.
     */
    public List<GeneratedExamGoal> generate(String context, String taskType, String taskDescription,
                                            String modelOverride) {
        return generate(context, taskType, taskDescription, "English", modelOverride);
    }

    public List<GeneratedExamGoal> generate(String context, String taskType, String taskDescription,
                                            String languageName, String modelOverride) {
        String contextSection = context == null || context.isBlank()
                ? ""
                : CONTEXT_SECTION.formatted(context);
        String prompt = PROMPT_TEMPLATE.formatted(
                languageName,
                contextSection,
                taskType == null || taskType.isBlank() ? "unspecified" : taskType,
                taskDescription);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        List<GeneratedExamGoal> goals = spec
                .user(prompt)
                .call()
                .entity(new ParameterizedTypeReference<List<GeneratedExamGoal>>() {});
        return goals == null ? List.of() : goals;
    }
}

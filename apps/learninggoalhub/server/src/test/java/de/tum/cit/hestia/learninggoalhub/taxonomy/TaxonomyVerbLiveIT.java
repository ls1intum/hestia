package de.tum.cit.hestia.learninggoalhub.taxonomy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Measures how often a classified Bloom level disagrees with the verb the goal is written with,
 * over a file of real goal texts, using the production prompt and batching.
 *
 * <p>The two are decided by different calls — extraction picks the verb with the source material in
 * view, classification reads the finished sentence — so they can drift apart, and the drift is what
 * this counts. It asserts nothing; it prints the rate and every disagreement for inspection.
 *
 * <p>Gated on {@code RUN_LIVE_SAIA=1} so it never runs in the normal build. Run with:
 * <pre>
 * RUN_LIVE_SAIA=1 SAIA_API_KEY=... TAXONOMY_GOALS=/path/goals.txt \
 *   ./gradlew test --tests '*TaxonomyVerbLiveIT' -i
 * </pre>
 *
 * <p>Optional: {@code TAXONOMY_MODEL}, {@code TAXONOMY_BATCH}.
 *
 * <p>The verb-to-level map is English only, which is enough for a diagnostic: a goal written in
 * another language simply goes uncounted rather than counted wrongly.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_SAIA", matches = "1")
class TaxonomyVerbLiveIT {

    /**
     * Verbs placed as Bloom's revised taxonomy places them, NOT as they read in ordinary English:
     * recognising and identifying are recall, which puts them at REMEMBER however technical the
     * object is, and distinguishing is differentiating, which is ANALYZE. A verb that the taxonomy
     * itself uses at more than one level — "deriving" sits at APPLY or CREATE depending on whether
     * the student follows a procedure or produces the result — is deliberately absent, so those
     * goals go uncounted rather than counted wrongly.
     */
    private static final Map<String, String> VERB_LEVEL = Map.ofEntries(
            Map.entry("naming", "REMEMBER"), Map.entry("recalling", "REMEMBER"),
            Map.entry("listing", "REMEMBER"), Map.entry("identifying", "REMEMBER"),
            Map.entry("recognizing", "REMEMBER"), Map.entry("recognising", "REMEMBER"),
            Map.entry("understanding", "UNDERSTAND"), Map.entry("explaining", "UNDERSTAND"),
            Map.entry("describing", "UNDERSTAND"), Map.entry("interpreting", "UNDERSTAND"),
            Map.entry("summarising", "UNDERSTAND"), Map.entry("summarizing", "UNDERSTAND"),
            Map.entry("applying", "APPLY"), Map.entry("computing", "APPLY"),
            Map.entry("implementing", "APPLY"), Map.entry("executing", "APPLY"),
            Map.entry("analyzing", "ANALYZE"), Map.entry("analysing", "ANALYZE"),
            Map.entry("distinguishing", "ANALYZE"), Map.entry("differentiating", "ANALYZE"),
            Map.entry("evaluating", "EVALUATE"), Map.entry("justifying", "EVALUATE"),
            Map.entry("designing", "CREATE"));

    @Test
    void countsGoalsWhoseLevelDisagreesWithTheirVerb() throws Exception {
        List<String> goals = Files.readAllLines(
                new File(System.getenv("TAXONOMY_GOALS")).toPath(), StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
        String model = System.getenv().getOrDefault("TAXONOMY_MODEL", "openai-gpt-oss-120b");
        int batch = Integer.parseInt(System.getenv().getOrDefault("TAXONOMY_BATCH", "20"));

        ChatClient.Builder builder = ChatClient.builder(OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(System.getenv().getOrDefault("SAIA_BASE_URL", "https://chat-ai.academiccloud.de"))
                        .apiKey(System.getenv("SAIA_API_KEY"))
                        .build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build());
        TaxonomyService service = new TaxonomyService(builder, 0.0);

        int checked = 0;
        int disagreed = 0;
        for (int start = 0; start < goals.size(); start += batch) {
            List<String> slice = goals.subList(start, Math.min(start + batch, goals.size()));
            List<TaxonomyClassification> levels = service.classifyBatch(slice, model);
            for (int i = 0; i < slice.size(); i++) {
                TaxonomyClassification level = i < levels.size() ? levels.get(i) : null;
                String expected = VERB_LEVEL.get(firstWord(slice.get(i)));
                if (expected == null || level == null || level.bloom() == null) {
                    continue;
                }
                checked++;
                if (!expected.equals(level.bloom().name())) {
                    disagreed++;
                    System.out.printf("%-10s verb says %-10s : %s%n",
                            level.bloom().name(), expected, slice.get(i));
                }
            }
            System.out.printf("... %d/%d classified%n", Math.min(start + batch, goals.size()), goals.size());
        }
        System.out.printf("%nModel %s: %d of %d checkable goals disagree with their own verb (%.1f%%)%n",
                model, disagreed, checked, checked == 0 ? 0.0 : 100.0 * disagreed / checked);
    }

    private static String firstWord(String goal) {
        int space = goal.indexOf(' ');
        return (space < 0 ? goal : goal.substring(0, space)).toLowerCase(Locale.ROOT);
    }
}

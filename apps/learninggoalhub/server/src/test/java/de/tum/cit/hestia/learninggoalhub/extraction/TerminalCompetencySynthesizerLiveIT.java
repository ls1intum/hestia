package de.tum.cit.hestia.learninggoalhub.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.learninggoalhub.extraction.TerminalCompetencySynthesizer.Candidate;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Manual run of the real {@link TerminalCompetencySynthesizer} against live SAIA, over the
 * apply+ goals of the deployed "Cloud Computing" course (snapshot in
 * {@code resources/terminal/cloud-computing-applyplus-goals.json}). Prints the emergent terminal
 * competencies and the candidate goals each subsumes — it asserts nothing; it is a "what comes out"
 * probe, not a CI test.
 *
 * <p>Gated on {@code RUN_LIVE_SAIA=1} (and reads {@code SAIA_API_KEY}, optional {@code SAIA_BASE_URL})
 * so it never runs in the normal build. Run with:
 * <pre>RUN_LIVE_SAIA=1 SAIA_API_KEY=... ./gradlew test --tests '*TerminalCompetencySynthesizerLiveIT' -i</pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_SAIA", matches = "1")
class TerminalCompetencySynthesizerLiveIT {

    @Test
    void printsTerminalCompetenciesForCloudComputing() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Default to the checked-in Cloud Computing snapshot; GOALS_FILE overrides with any external
        // apply+ export (used to validate generalisation on a second-domain course).
        String goalsFile = System.getenv("GOALS_FILE");
        Candidate[] arr = (goalsFile != null && !goalsFile.isBlank())
                ? mapper.readValue(new java.io.File(goalsFile), Candidate[].class)
                : loadResource(mapper, "/terminal/cloud-computing-applyplus-goals.json");
        List<Candidate> candidates = List.of(arr);
        String label = System.getenv().getOrDefault("GOALS_LABEL", "Cloud Computing");

        String baseUrl = System.getenv().getOrDefault("SAIA_BASE_URL", "https://chat-ai.academiccloud.de");
        String apiKey = System.getenv("SAIA_API_KEY");
        String model = System.getenv().getOrDefault("SAIA_MODEL", "openai-gpt-oss-120b");

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.2).build())
                .build();
        ChatClient.Builder builder = ChatClient.builder(chatModel);

        TerminalCompetencySynthesizer synthesizer = new TerminalCompetencySynthesizer(builder);
        List<TerminalCompetency> competencies = synthesizer.synthesize(candidates, null);

        System.out.println("\n===== TERMINAL COMPETENCIES (" + label + ", " + model + ") =====");
        System.out.println(candidates.size() + " apply+ candidates -> " + competencies.size() + " competencies\n");
        int n = 1;
        for (TerminalCompetency c : competencies) {
            System.out.println(n++ + ". " + c.text());
            for (int idx : c.supporting()) {
                if (idx >= 0 && idx < candidates.size()) {
                    Candidate cand = candidates.get(idx);
                    System.out.println("     [" + idx + "] (" + cand.bloomLevel() + ") " + cand.text());
                }
            }
            System.out.println();
        }
        // Surface any candidate no competency claimed — these are the dropped / unmapped ones.
        boolean[] used = new boolean[candidates.size()];
        for (TerminalCompetency c : competencies) {
            for (int idx : c.supporting()) {
                if (idx >= 0 && idx < used.length) {
                    used[idx] = true;
                }
            }
        }
        System.out.println("----- candidates not claimed by any competency (dropped/unmapped) -----");
        for (int i = 0; i < candidates.size(); i++) {
            if (!used[i]) {
                System.out.println("     [" + i + "] (" + candidates.get(i).bloomLevel() + ") " + candidates.get(i).text());
            }
        }
        System.out.println("=====================================================================\n");
    }

    private static Candidate[] loadResource(ObjectMapper mapper, String path) throws Exception {
        try (InputStream in = TerminalCompetencySynthesizerLiveIT.class.getResourceAsStream(path)) {
            return mapper.readValue(in, Candidate[].class);
        }
    }
}

package de.tum.cit.hestia.learninggoalhub.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

/**
 * Compares vision models and batch sizes for {@link PageDescriptionService}'s figure descriptions,
 * over every page of a PDF given by path. Uses the production prompt, render DPI and structured
 * reply type, so a model that cannot hold the JSON contract fails here the way it would in a run.
 * It asserts nothing; it prints latency and the descriptions for qualitative comparison.
 *
 * <p>Gated on {@code RUN_LIVE_SAIA=1} so it never runs in the normal build. Run with:
 * <pre>
 * RUN_LIVE_SAIA=1 SAIA_API_KEY=... FIGURE_PDF=/path/to.pdf \
 *   ./gradlew test --tests '*PageDescriptionModelLiveIT' -i
 * </pre>
 *
 * <p>Optional: {@code FIGURE_MODELS} (comma-separated ids), {@code FIGURE_BATCHES} (comma-separated
 * batch sizes), {@code FIGURE_PAGES} (comma-separated 1-based pages; default all),
 * {@code FIGURE_OUT} (JSON result file).
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_SAIA", matches = "1")
class PageDescriptionModelLiveIT {

    @Test
    void comparesVisionModelsOverEveryPage() throws Exception {
        byte[] pdfBytes = Files.readAllBytes(new File(System.getenv("FIGURE_PDF")).toPath());
        List<String> models = csv(System.getenv().getOrDefault("FIGURE_MODELS",
                "qwen3.6-35b-a3b,qwen3.5-122b-a10b,qwen3.5-397b-a17b,gemma-4-31b-it"));
        List<Integer> batches = csv(System.getenv().getOrDefault("FIGURE_BATCHES", "8")).stream()
                .map(Integer::parseInt).toList();

        List<Result> results = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<Integer> pages = pagesToDescribe(pdf.getNumberOfPages());
            // Rendering is deterministic and model-independent, so every combination sees the same
            // images and the measured time is the provider's alone.
            Map<Integer, Media> rendered = new LinkedHashMap<>();
            for (int page : pages) {
                rendered.put(page, render(renderer, page));
            }
            System.out.println("Rendered " + pages.size() + " page(s) at "
                    + PageDescriptionService.RENDER_DPI + " dpi from " + System.getenv("FIGURE_PDF"));

            for (String model : models) {
                for (int batchSize : batches) {
                    results.add(run(model, batchSize, pages, rendered));
                }
            }
        }
        printSummary(results);
        String out = System.getenv("FIGURE_OUT");
        if (out != null && !out.isBlank()) {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(out), results);
            System.out.println("Wrote " + out);
        }
    }

    private Result run(String model, int batchSize, List<Integer> pages, Map<Integer, Media> rendered) {
        ChatClient chatClient = ChatClient.builder(OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(System.getenv().getOrDefault("SAIA_BASE_URL", "https://chat-ai.academiccloud.de"))
                        .apiKey(System.getenv("SAIA_API_KEY"))
                        .build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build()).build();

        System.out.println("\n===== " + model + " (batch " + batchSize + ") =====");
        List<PageDescriptionService.PageReply> all = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        long started = System.nanoTime();
        for (int start = 0; start < pages.size(); start += batchSize) {
            List<Integer> batch = pages.subList(start, Math.min(start + batchSize, pages.size()));
            long batchStarted = System.nanoTime();
            try {
                List<PageDescriptionService.PageReply> replies = chatClient.prompt()
                        .user(u -> u.text(PageDescriptionService.PROMPT.formatted(batch.stream()
                                        .map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")))
                                .media(batch.stream().map(rendered::get).toArray(Media[]::new)))
                        .call()
                        .entity(new ParameterizedTypeReference<List<PageDescriptionService.PageReply>>() {});
                all.addAll(replies == null ? List.of() : replies);
                System.out.printf("  pages %d-%d: %d replies in %.1f s%n", batch.getFirst(), batch.getLast(),
                        replies == null ? 0 : replies.size(), seconds(batchStarted));
            } catch (RuntimeException e) {
                failures.add("pages " + batch.getFirst() + "-" + batch.getLast() + ": " + e.getMessage());
                System.out.printf("  pages %d-%d: FAILED after %.1f s: %s%n", batch.getFirst(), batch.getLast(),
                        seconds(batchStarted), e.getMessage());
            }
        }
        double elapsed = seconds(started);
        for (PageDescriptionService.PageReply reply : all) {
            System.out.println("  [p" + reply.page() + (Boolean.FALSE.equals(reply.teachesContent()) ? " skip" : "")
                    + "] " + reply.description());
        }
        return Result.of(model, batchSize, pages.size(), elapsed, all, failures);
    }

    private static Media render(PDFRenderer renderer, int page) throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(page - 1, PageDescriptionService.RENDER_DPI);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png.toByteArray()));
    }

    private static List<Integer> pagesToDescribe(int pageCount) {
        String requested = System.getenv("FIGURE_PAGES");
        if (requested == null || requested.isBlank()) {
            return java.util.stream.IntStream.rangeClosed(1, pageCount).boxed().toList();
        }
        return csv(requested).stream().map(Integer::parseInt).toList();
    }

    private static List<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static double seconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    private void printSummary(List<Result> results) {
        System.out.println("\n===== SUMMARY =====");
        System.out.printf("%-26s %6s %9s %9s %8s %8s %8s%n",
                "model", "batch", "total s", "s/page", "covered", "avg len", "teaches");
        for (Result r : results) {
            System.out.printf("%-26s %6d %9.1f %9.1f %8s %8d %8d%n", r.model(), r.batchSize(),
                    r.totalSeconds(), r.secondsPerPage(), r.pagesCovered() + "/" + r.pagesRequested(),
                    r.averageDescriptionLength(), r.teachingPages());
        }
        for (Result r : results) {
            for (String failure : r.failures()) {
                System.out.println("  ! " + r.model() + " batch " + r.batchSize() + " -> " + failure);
            }
        }
    }

    /** One model/batch combination's outcome, also the JSON shape written to {@code FIGURE_OUT}. */
    record Result(String model, int batchSize, int pagesRequested, int pagesCovered, double totalSeconds,
                  double secondsPerPage, int averageDescriptionLength, int teachingPages,
                  List<PageDescriptionService.PageReply> replies, List<String> failures) {

        static Result of(String model, int batchSize, int pagesRequested, double totalSeconds,
                         List<PageDescriptionService.PageReply> replies, List<String> failures) {
            List<PageDescriptionService.PageReply> usable = replies.stream()
                    .filter(r -> r != null && r.page() != null && r.description() != null && !r.description().isBlank())
                    .toList();
            int averageLength = usable.isEmpty() ? 0
                    : (int) usable.stream().mapToInt(r -> r.description().length()).average().orElse(0);
            int teaching = (int) usable.stream().filter(r -> !Boolean.FALSE.equals(r.teachesContent())).count();
            return new Result(model, batchSize, pagesRequested, usable.size(), totalSeconds,
                    pagesRequested == 0 ? 0 : totalSeconds / pagesRequested, averageLength, teaching,
                    usable, failures);
        }
    }
}

package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.llm.LlmClient;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class BookmarkRelevanceJudgeTest {

    // The chat client is never reached: every case here is resolved by the mechanical pre-filter. The
    // grey-zone (model) path is covered where the judge is mocked by callers.
    private final BookmarkRelevanceJudge judge = mechanicalJudge();

    private static BookmarkRelevanceJudge mechanicalJudge() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new BookmarkRelevanceJudge(new LlmClient(builder), "test-model", Duration.ofSeconds(30));
    }

    @Test
    void singleWhenFewerThanTwoBookmarks() {
        assertThat(judge.shouldSplit("9.pdf", 63,
                List.of("Shared Responsibility Model"))).isFalse();
    }

    @Test
    void singleWhenPowerPointDefaultSectionPresent() {
        // "Standardabschnitt" is PowerPoint's auto-generated section name → a single exported deck
        // (DevOps W06/W08/W11), even with several custom-named sections after it.
        assertThat(judge.shouldSplit("W06 Continuous Delivery and Deployment.pdf", 55,
                List.of("Standardabschnitt", "CI recap", "Continuous delivery",
                        "Deployment strategies", "Live testing"))).isFalse();
    }

    @Test
    void singleWhenMajorityArePerSlideBookmarks() {
        // PowerPoint exported one bookmark per slide (Cloud Computing "10 Cloud Deployment.pdf").
        List<String> perSlide = IntStream.rangeClosed(1, 38)
                .mapToObj(i -> "Slide " + i + ": some content").toList();
        assertThat(judge.shouldSplit("10 Cloud Deployment.pdf", 38, perSlide)).isFalse();
    }

    @Test
    void singleWhenBookmarkCountApproachesPageCount() {
        // Custom-named but one-per-page bookmarks: count ≈ pages means per-slide markers.
        List<String> titles = IntStream.rangeClosed(1, 18)
                .mapToObj(i -> "Topic " + i).toList();
        assertThat(judge.shouldSplit("deck.pdf", 20, titles)).isFalse();
    }

    /**
     * Real failure mode: with the model gateway unreachable, the judge's call spent minutes in
     * connect timeouts and retries while the upload request that runs it showed "Uploading… 60%".
     * Past its deadline the judge gives up and splits, as it does when the call fails.
     */
    @Test
    void splitsWhenTheModelDoesNotAnswerBeforeTheDeadline() {
        LlmClient hanging = mock(LlmClient.class);
        when(hanging.text(any())).thenAnswer(call -> {
            Thread.sleep(60_000);
            return "SINGLE";
        });
        BookmarkRelevanceJudge judge = new BookmarkRelevanceJudge(hanging, "test-model", Duration.ofMillis(200));

        long start = System.nanoTime();
        boolean split = judge.shouldSplit("algorithms.pdf", 40, List.of("Lecture 1: Sorting", "Lecture 2: Searching"));

        assertThat(split).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void parseVerdictReadsTheSingleWord() {
        assertThat(BookmarkRelevanceJudge.parseVerdict("SPLIT")).isTrue();
        assertThat(BookmarkRelevanceJudge.parseVerdict("single")).isFalse();
        assertThat(BookmarkRelevanceJudge.parseVerdict("The answer is SINGLE.")).isFalse();
    }

    @Test
    void parseVerdictIsNullWhenUnclear() {
        assertThat(BookmarkRelevanceJudge.parseVerdict(null)).isNull();
        assertThat(BookmarkRelevanceJudge.parseVerdict("maybe")).isNull();
        // Both words present is no decision.
        assertThat(BookmarkRelevanceJudge.parseVerdict("SPLIT or SINGLE")).isNull();
    }
}

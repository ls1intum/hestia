package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        return new BookmarkRelevanceJudge(builder, "test-model");
    }

    @Test
    void singleWhenFewerThanThreeBookmarks() {
        // A guest talk with a 2-entry agenda is one session, not two lectures (Cloud Computing 9.pdf).
        assertThat(judge.shouldSplit("9.pdf", 63,
                List.of("Shared Responsibility Model", "Identity and Workload Orchestration"))).isFalse();
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

package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

    @Test
    void returnsSingleChunkWhenTextFits() {
        DocumentChunker chunker = new DocumentChunker(100, 10);
        List<String> chunks = chunker.chunk("short text");
        assertThat(chunks).containsExactly("short text");
    }

    @Test
    void returnsEmptyListForNullOrEmptyText() {
        DocumentChunker chunker = new DocumentChunker(100, 10);
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
    }

    @Test
    void splitsLongTextWithOverlap() {
        DocumentChunker chunker = new DocumentChunker(10, 3);
        String text = "abcdefghijklmnopqrstuvwxyz"; // 26 chars
        List<String> chunks = chunker.chunk(text);

        // step = 10 - 3 = 7; starts at 0, 7, 14, 21 -> ends 10, 17, 24, 26
        assertThat(chunks).containsExactly(
                "abcdefghij",
                "hijklmnopq",
                "opqrstuvwx",
                "vwxyz"
        );

        // adjacent chunks share `overlap` characters
        assertThat(chunks.get(0).substring(chunks.get(0).length() - 3))
                .isEqualTo(chunks.get(1).substring(0, 3));
    }

    @Test
    void coversAllCharactersAtLeastOnce() {
        DocumentChunker chunker = new DocumentChunker(8000, 500);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25_000; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String text = sb.toString();
        List<String> chunks = chunker.chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).charAt(0)).isEqualTo(text.charAt(0));
        assertThat(chunks.get(chunks.size() - 1).charAt(chunks.get(chunks.size() - 1).length() - 1))
                .isEqualTo(text.charAt(text.length() - 1));
    }

    @Test
    void disabledWhenChunkSizeNonPositive() {
        DocumentChunker chunker = new DocumentChunker(0, 0);
        String text = "a".repeat(50_000);
        assertThat(chunker.chunk(text)).containsExactly(text);
    }

    @Test
    void rejectsOverlapEqualOrLargerThanChunkSize() {
        assertThatThrownBy(() -> new DocumentChunker(100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentChunker(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public DocumentChunker(@Value("${hestia.extraction.chunk-size:8000}") int chunkSize,
                           @Value("${hestia.extraction.chunk-overlap:500}") int chunkOverlap) {
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("hestia.extraction.chunk-overlap must be >= 0");
        }
        if (chunkSize > 0 && chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("hestia.extraction.chunk-overlap must be smaller than chunk-size");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /** A chunk of document text together with its start offset in the original document. */
    public record Chunk(int start, String text) {
    }

    public List<String> chunk(String text) {
        return chunkWithOffsets(text).stream().map(Chunk::text).toList();
    }

    public List<Chunk> chunkWithOffsets(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (chunkSize <= 0 || text.length() <= chunkSize) {
            return List.of(new Chunk(0, text));
        }
        List<Chunk> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(new Chunk(start, text.substring(start, end)));
            if (end == text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }
}

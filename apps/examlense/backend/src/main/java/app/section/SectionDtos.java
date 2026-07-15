package app.section;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response DTOs for the section slice (sections, context/figure blocks, figures). */
public final class SectionDtos {

    private SectionDtos() {}

    public record SectionDto(
        UUID id, UUID exam_id, int position, String name,
        OffsetDateTime confirmed_at, OffsetDateTime solve_started_at,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static SectionDto from(Section s) {
            return new SectionDto(s.getId(), s.getExamId(), s.getPosition(), s.getName(),
                s.getConfirmedAt(), s.getSolveStartedAt(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record BlockDto(
        UUID id, UUID exam_id, UUID section_id, int position, String content, String kind,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static BlockDto from(SectionBlock b) {
            return new BlockDto(b.getId(), b.getExamId(), b.getSectionId(), b.getPosition(),
                b.getContent(), b.getKind(), b.getCreatedAt(), b.getUpdatedAt());
        }
    }

    public record FigureDto(
        UUID id, UUID block_id, String storage_path, String caption, int position,
        String source, OffsetDateTime created_at
    ) {
        public static FigureDto from(SectionFigure f) {
            return new FigureDto(f.getId(), f.getBlockId(), f.getStoragePath(), f.getCaption(),
                f.getPosition(), f.getSource(), f.getCreatedAt());
        }
    }
}

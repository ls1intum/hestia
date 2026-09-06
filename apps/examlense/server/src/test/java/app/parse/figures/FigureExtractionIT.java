package app.parse.figures;

import app.AbstractIntegrationTest;
import app.exam.Exam;
import app.exam.ExamRepository;
import app.section.Section;
import app.section.SectionBlock;
import app.section.SectionBlockRepository;
import app.section.SectionFigure;
import app.section.SectionFigureRepository;
import app.section.SectionRepository;
import app.shared.DefaultUser;
import app.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chain against a real database and real filesystem storage: a figure
 * block with no image gets one cropped out of the PDF, and that image is then
 * retrievable through exactly the endpoints the editor uses to render it.
 *
 * <p>The unit tests mock storage and the repositories, so this is the only place
 * the FK on {@code section_figures.block_id}, the real file writes, and the
 * signed-URL round trip are exercised together.
 */
@AutoConfigureMockMvc
class FigureExtractionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExamRepository exams;
    @Autowired SectionRepository sections;
    @Autowired SectionBlockRepository blocks;
    @Autowired SectionFigureRepository figures;
    @Autowired StorageService storage;
    @Autowired FigureExtractionService extraction;

    private record Fixture(Exam exam, SectionBlock block, String pdfPath) {}

    /** An exam whose page 1 holds one pasted image, plus an empty figure block for it. */
    private Fixture seed() {
        Exam exam = new Exam();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("pdf");
        exam.setStatus("draft");
        exam.setParsedAt(OffsetDateTime.now());
        exam = exams.save(exam);

        Section section = new Section();
        section.setExamId(exam.getId());
        section.setPosition(1);
        section.setName("Teil A");
        section = sections.save(section);

        SectionBlock block = new SectionBlock();
        block.setExamId(exam.getId());
        block.setSectionId(section.getId());
        block.setKind("figure");
        block.setPosition(1);
        block.setContent("Abbildung 1 — Aufbau");
        block = blocks.save(block);

        byte[] pdf = TestPdfs.page((doc, cs) -> {
            TestPdfs.text(cs, 72, 780, "Aufgabe 1: Betrachten Sie die Abbildung.");
            cs.drawImage(TestPdfs.image(doc, 480, 360), 120, 430, 250, 190);
        });
        String pdfPath = DefaultUser.ID + "/" + exam.getId() + ".pdf";
        storage.store("exam-pdfs", pdfPath, pdf);
        return new Fixture(exam, block, pdfPath);
    }

    @Test
    void aParsedFigureBlockEndsUpRenderableThroughTheApi() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + TEST_TOKEN;

        // Before: the block is the empty "upload a screenshot" placeholder.
        mvc.perform(get("/api/blocks/" + f.block().getId() + "/figures").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        extraction.extract(f.exam().getId(), DefaultUser.ID.toString(), f.pdfPath(),
            List.of(new FigurePlacement(f.block().getId(), 1, "Abbildung 1", 0)));

        // A row landed, marked as machine-extracted and under the auto/ prefix.
        List<SectionFigure> rows = figures.findByBlockIdOrderByPositionAsc(f.block().getId());
        assertThat(rows).hasSize(1);
        SectionFigure row = rows.get(0);
        assertThat(row.getSource()).isEqualTo("pdf");
        assertThat(row.getStoragePath())
            .startsWith(DefaultUser.ID + "/" + f.exam().getId() + "/auto/")
            .endsWith(".png");

        // The bytes really are on disk and really are an image.
        byte[] stored = storage.download("exam-figures", row.getStoragePath());
        assertThat(stored).isNotNull();
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();

        // And the editor's own endpoints now serve it.
        mvc.perform(get("/api/blocks/" + f.block().getId() + "/figures").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].source").value("pdf"));

        String signed = com.jayway.jsonpath.JsonPath.read(
            mvc.perform(get("/api/figures/" + row.getId() + "/signed-url").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.signed_url");

        byte[] served = mvc.perform(get(signed))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(stored);
    }

    @Test
    void deletingTheBlockCascadesTheExtractedFigureRowAway() {
        Fixture f = seed();
        extraction.extract(f.exam().getId(), DefaultUser.ID.toString(), f.pdfPath(),
            List.of(new FigurePlacement(f.block().getId(), 1, "Abbildung 1", 0)));
        assertThat(figures.findByBlockIdOrderByPositionAsc(f.block().getId())).hasSize(1);

        blocks.deleteById(f.block().getId());

        assertThat(figures.findByBlockIdOrderByPositionAsc(f.block().getId())).isEmpty();
    }

    @Test
    void writesNoRowForABlockThatNoLongerExists() {
        // A re-parse cascades the old blocks away mid-run; the FK must stop us and
        // the failure must stay contained rather than bubbling out of extraction.
        Fixture f = seed();
        UUID ghost = UUID.randomUUID();

        extraction.extract(f.exam().getId(), DefaultUser.ID.toString(), f.pdfPath(),
            List.of(new FigurePlacement(ghost, 1, "Abbildung 1", 0)));

        assertThat(figures.findByBlockIdOrderByPositionAsc(ghost)).isEmpty();
    }
}

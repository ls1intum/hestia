package de.tum.cit.hestia.assessment.parse;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.section.Section;
import de.tum.cit.hestia.assessment.section.SectionBlock;
import de.tum.cit.hestia.assessment.section.SectionBlockRepository;
import de.tum.cit.hestia.assessment.section.SectionFigure;
import de.tum.cit.hestia.assessment.section.SectionFigureRepository;
import de.tum.cit.hestia.assessment.section.SectionRepository;
import de.tum.cit.hestia.assessment.shared.DefaultUser;
import de.tum.cit.hestia.assessment.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedExaminationPersisterIT extends AbstractIntegrationTest {

    @Autowired ParsedExaminationPersister persister;
    @Autowired ExaminationRepository exams;
    @Autowired SectionRepository sections;
    @Autowired SectionBlockRepository blocks;
    @Autowired SectionFigureRepository figures;
    @Autowired StorageService storage;

    @Test
    void reparseRemovesObjectsForReplacedFigureRows() {
        Examination exam = new Examination();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("pdf");
        exam.setStatus("parsing");
        exams.save(exam);

        Section oldSection = new Section();
        oldSection.setExamId(exam.getId());
        oldSection.setPosition(0);
        sections.save(oldSection);
        SectionBlock oldBlock = new SectionBlock();
        oldBlock.setExamId(exam.getId());
        oldBlock.setSectionId(oldSection.getId());
        oldBlock.setPosition(0);
        oldBlock.setKind("figure");
        blocks.save(oldBlock);
        SectionFigure oldFigure = new SectionFigure();
        oldFigure.setBlockId(oldBlock.getId());
        oldFigure.setPosition(0);
        oldFigure.setSource("pdf");
        String path = DefaultUser.ID + "/" + exam.getId() + "/" + oldFigure.getId() + ".png";
        oldFigure.setStoragePath(path);
        figures.save(oldFigure);
        storage.store("exam-figures", path, "old-image".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> parsed = new HashMap<>();
        parsed.put("title", "Reparsed");
        parsed.put("sections", List.of(Map.of("name", "New section")));
        Map<String, Object> task = new HashMap<>();
        task.put("section", "New section");
        task.put("prompt", "New task");
        task.put("type", "text");
        ParseAttempt attempt = new ParseAttempt(
            exam.getId(), DefaultUser.ID.toString(), "openai:gpt-5.5", "text");

        ParsedExaminationPersister.PersistResult result =
            persister.persist(attempt, parsed, List.of(task));

        assertThat(result.ok()).isTrue();
        assertThat(figures.findById(oldFigure.getId())).isEmpty();
        assertThat(storage.download("exam-figures", path)).isNull();
    }
}

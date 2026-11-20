package de.tum.cit.hestia.materials.document;

import de.tum.cit.hestia.materials.TestTenant;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@org.springframework.transaction.annotation.Transactional
@Import(MaterialsTestConfiguration.class)
class DocumentRepositoryTest extends MaterialsTestBase {

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void persistsCourseWithMultipleDocuments() {
        UUID course = TestTenant.course("Software Engineering");

        documentRepository.save(new Document(course, "lecture-01.pdf", "application/pdf", "intro text"));
        documentRepository.save(new Document(course, "exercise-01.pdf", "application/pdf", "exercise text"));

        List<Document> documents = documentRepository.findByCourseId(course);

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getFilename)
                .containsExactlyInAnyOrder("lecture-01.pdf", "exercise-01.pdf");
        assertThat(documents).allSatisfy(d -> {
            assertThat(d.getCourseId()).isEqualTo(course);
            assertThat(d.getUploadedAt()).isNotNull();
        });
    }
}

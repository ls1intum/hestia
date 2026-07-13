package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class DocumentRepositoryTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void persistsCourseWithMultipleDocuments() {
        Course course = courseRepository.save(new Course("Software Engineering"));

        documentRepository.save(new Document(course, "lecture-01.pdf", "application/pdf", "intro text"));
        documentRepository.save(new Document(course, "exercise-01.pdf", "application/pdf", "exercise text"));

        List<Document> documents = documentRepository.findByCourseId(course.getId());

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::getFilename)
                .containsExactlyInAnyOrder("lecture-01.pdf", "exercise-01.pdf");
        assertThat(documents).allSatisfy(d -> {
            assertThat(d.getCourse().getId()).isEqualTo(course.getId());
            assertThat(d.getUploadedAt()).isNotNull();
        });
        assertThat(course.getCreatedAt()).isNotNull();
    }
}

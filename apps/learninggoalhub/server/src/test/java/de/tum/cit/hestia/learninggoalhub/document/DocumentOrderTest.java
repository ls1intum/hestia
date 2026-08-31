package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentOrderTest {

    private final Course course = new Course("Course");

    @Test
    void ordersNumberedLectureFilesNaturally() {
        List<Document> documents = new ArrayList<>(List.of(
                document("Vorlesung10.pdf"), document("Vorlesung2.pdf"), document("Vorlesung1.pdf")));

        documents.sort(DocumentOrder.comparator());

        assertThat(documents).extracting(Document::getFilename)
                .containsExactly("Vorlesung1.pdf", "Vorlesung2.pdf", "Vorlesung10.pdf");
    }

    @Test
    void usesInstructorDisplayNameAndIgnoresCase() {
        Document later = document("a.pdf");
        later.setDisplayName("W11 Advanced");
        Document earlier = document("z.pdf");
        earlier.setDisplayName("w02 Foundations");
        List<Document> documents = new ArrayList<>(List.of(later, earlier));

        documents.sort(DocumentOrder.comparator());

        assertThat(documents).containsExactly(earlier, later);
    }

    @Test
    void leavesTheOnlyCombinedDocumentUntouched() {
        Document combined = document("complete-course.pdf");
        List<Document> documents = new ArrayList<>(List.of(combined));

        documents.sort(DocumentOrder.comparator());

        assertThat(documents).containsExactly(combined);
    }

    @Test
    void preservesUploadOrderWhenNamesDoNotProvideDifferentSequenceNumbers() {
        Document lecture = document("lecture-01.pdf");
        Document exercise = document("exercise-01.pdf");
        List<Document> documents = new ArrayList<>(List.of(lecture, exercise));

        documents.sort(DocumentOrder.comparator());

        assertThat(documents).containsExactly(lecture, exercise);
    }

    private Document document(String filename) {
        return new Document(course, filename, "application/pdf", "text");
    }
}

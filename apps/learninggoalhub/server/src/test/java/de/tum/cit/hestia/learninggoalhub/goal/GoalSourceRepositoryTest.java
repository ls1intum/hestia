package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class GoalSourceRepositoryTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Test
    void goalCanBeAttributedToMultipleDocuments() {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise text"));

        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Students can apply TDD.", GoalKind.EXPLICIT));

        goalSourceRepository.save(new GoalSource(goal, lecture, "...write a failing test first..."));
        goalSourceRepository.save(new GoalSource(goal, exercise, "...practise red-green-refactor..."));

        List<GoalSource> sources = goalSourceRepository.findByGoalId(goal.getId());

        assertThat(sources).hasSize(2);
        assertThat(sources).extracting(s -> s.getDocument().getFilename())
                .containsExactlyInAnyOrder("lecture.pdf", "exercise.pdf");
        assertThat(sources).extracting(GoalSource::getSnippet)
                .allMatch(s -> s.startsWith("..."));
    }
}

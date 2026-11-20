package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.materials.TestTenant;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.GoalFixtures;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.DocumentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@org.springframework.transaction.annotation.Transactional
@Import(MaterialsTestConfiguration.class)
class GoalSourceRepositoryTest extends MaterialsTestBase {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Test
    void goalCanBeAttributedToMultipleDocuments() {
        UUID course = TestTenant.course("Software Engineering");
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise text"));

        PlacedGoal goal = GoalFixtures.goal(course, "Students can apply TDD.").create();

        goalSourceRepository.save(new GoalSource(goal.id(), lecture, "...write a failing test first..."));
        goalSourceRepository.save(new GoalSource(goal.id(), exercise, "...practise red-green-refactor..."));

        List<GoalSource> sources = goalSourceRepository.findByIdGoalId(goal.id());

        assertThat(sources).hasSize(2);
        assertThat(sources).extracting(s -> s.getDocument().getFilename())
                .containsExactlyInAnyOrder("lecture.pdf", "exercise.pdf");
        assertThat(sources).extracting(GoalSource::getSnippet)
                .allMatch(s -> s.startsWith("..."));
    }
}

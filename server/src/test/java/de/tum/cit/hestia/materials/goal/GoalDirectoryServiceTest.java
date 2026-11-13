package de.tum.cit.hestia.materials.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.hestia.core.spi.GoalDirectory;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.course.Course;
import de.tum.cit.hestia.materials.course.CourseRepository;
import de.tum.cit.hestia.materials.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// As in production: without open-in-view, a lazy association read outside a transaction fails here too.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
class GoalDirectoryServiceTest extends MaterialsTestBase {

    @Autowired
    private GoalDirectory directory;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalRelationshipRepository relationshipRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Test
    void createdCoursesAreListedNewestFirst() {
        GoalDirectory.Course first = directory.createCourse("Directory: first");
        GoalDirectory.Course second = directory.createCourse("Directory: second");

        GoalDirectory.Page<GoalDirectory.Course> page = directory.listCourses(0, 2);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(page.content()).containsExactly(second, first);
        assertThat(page.number()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(2);
        assertThat(page.totalPages()).isGreaterThanOrEqualTo(1);
        assertThat(courseRepository.findById(first.id())).get().extracting(Course::getName)
                .isEqualTo("Directory: first");
    }

    @Test
    void goalsCarryEnumNamesHierarchyAndRelationships() {
        Course course = courseRepository.save(new Course("Directory: goals"));
        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));
        LearningGoal tdd = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        tdd.setHierarchyNode(session);
        tdd.setBloomLevel(BloomLevel.APPLY);
        tdd.setSoloLevel(SoloLevel.RELATIONAL);
        tdd.setRole(GoalRole.SKILL);
        tdd = goalRepository.save(tdd);
        LearningGoal loose = goalRepository.save(
                new LearningGoal(course, "Understand unit testing.", GoalKind.IMPLICIT));
        relationshipRepository.save(new GoalRelationship(
                loose, tdd, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        List<GoalDirectory.Goal> goals = directory.goals(course.getId());

        assertThat(goals).extracting(GoalDirectory.Goal::id).containsExactly(tdd.getId(), loose.getId());
        GoalDirectory.Goal first = goals.get(0);
        assertThat(first.text()).isEqualTo("Apply TDD.");
        assertThat(first.kind()).isEqualTo("EXPLICIT");
        assertThat(first.role()).isEqualTo("SKILL");
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.bloomLevel()).isEqualTo("APPLY");
        assertThat(first.soloLevel()).isEqualTo("RELATIONAL");
        assertThat(first.hierarchy())
                .isEqualTo(new GoalDirectory.Hierarchy("Software Engineering", "Session 3: Testing", null));
        assertThat(first.relationships()).isEmpty();

        GoalDirectory.Goal second = goals.get(1);
        assertThat(second.hierarchy()).as("a goal without a hierarchy node").isNull();
        assertThat(second.bloomLevel()).isNull();
        assertThat(second.relationships()).containsExactly(new GoalDirectory.Relationship(
                "CONTRIBUTES_TO", tdd.getId(), "Apply TDD.", 1.0, "HIERARCHY"));

        assertThat(directory.goal(course.getId(), tdd.getId())).isEqualTo(first);
        assertThat(directory.goal(course.getId(), loose.getId())).isEqualTo(second);
    }

    @Test
    void goalsBySessionGroupsByNodeWithUngroupedLast() {
        Course course = courseRepository.save(new Course("Directory: by session"));
        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Module"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 1"));
        LearningGoal grouped = new LearningGoal(course, "Grouped goal.", GoalKind.EXPLICIT);
        grouped.setHierarchyNode(session);
        grouped = goalRepository.save(grouped);
        goalRepository.save(new LearningGoal(course, "Ungrouped goal.", GoalKind.EXPLICIT));

        List<GoalDirectory.SessionGoals> groups = directory.goalsBySession(course.getId());

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).nodeId()).isEqualTo(session.getId());
        assertThat(groups.get(0).level()).isEqualTo("SESSION");
        assertThat(groups.get(0).label()).isEqualTo("Session 1");
        assertThat(groups.get(0).goals()).extracting(GoalDirectory.Goal::id).containsExactly(grouped.getId());
        assertThat(groups.get(1).nodeId()).isNull();
        assertThat(groups.get(1).level()).isNull();
        assertThat(groups.get(1).label()).isNull();
        assertThat(groups.get(1).goals()).extracting(GoalDirectory.Goal::text).containsExactly("Ungrouped goal.");
    }

    @Test
    void deleteGoalRemovesItAndAGoneGoalIsNotAnError() {
        Course course = courseRepository.save(new Course("Directory: delete"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Delete me.", GoalKind.EXPLICIT));

        directory.deleteGoal(course.getId(), goal.getId());

        assertThat(goalRepository.findById(goal.getId())).isEmpty();
        assertThatCode(() -> directory.deleteGoal(course.getId(), goal.getId())).doesNotThrowAnyException();
    }

    @Test
    void goalOfAnotherCourseCannotBeReadOrDeleted() {
        Course course = courseRepository.save(new Course("Directory: mine"));
        Course other = courseRepository.save(new Course("Directory: other"));
        LearningGoal goal = goalRepository.save(new LearningGoal(other, "Not yours.", GoalKind.EXPLICIT));

        assertThatThrownBy(() -> directory.goal(course.getId(), goal.getId()))
                .isInstanceOf(NoSuchElementException.class);
        directory.deleteGoal(course.getId(), goal.getId());
        assertThat(goalRepository.findById(goal.getId())).isPresent();
    }

    @Test
    void unknownCourseIsNoSuchElement() {
        long unknown = 987_654_321L;

        assertThatThrownBy(() -> directory.goals(unknown)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> directory.goalsBySession(unknown)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> directory.goal(unknown, 1)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> directory.deleteGoal(unknown, 1)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void unknownGoalIsNoSuchElement() {
        Course course = courseRepository.save(new Course("Directory: no goal"));

        assertThatThrownBy(() -> directory.goal(course.getId(), 987_654_321L))
                .isInstanceOf(NoSuchElementException.class);
    }
}

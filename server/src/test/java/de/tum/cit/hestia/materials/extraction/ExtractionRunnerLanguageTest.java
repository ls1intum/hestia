package de.tum.cit.hestia.materials.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.topictree.TopicTreeService;
import de.tum.cit.hestia.materials.course.Course;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.LanguageUtils;
import de.tum.cit.hestia.materials.goal.GoalKind;
import de.tum.cit.hestia.materials.goal.GoalRole;
import de.tum.cit.hestia.materials.goal.LearningGoal;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionRunnerLanguageTest {

    @Test
    void resolvesCourseOverrideBeforeDocumentDominantAndFallback() {
        Course course = new Course("Course");
        course.setOutputLanguage("de");

        assertThat(LanguageUtils.resolveLanguage(course, "en", "fr")).isEqualTo("de");
    }

    @Test
    void resolvesDocumentLanguageBeforeDominantAndFallback() {
        Course course = new Course("Course");

        assertThat(LanguageUtils.resolveLanguage(course, "de", "en")).isEqualTo("de");
    }

    @Test
    void resolvesDominantLanguageBeforeEnglishFallback() {
        Course course = new Course("Course");

        assertThat(LanguageUtils.resolveLanguage(course, null, "de")).isEqualTo("de");
        assertThat(LanguageUtils.resolveLanguage(course, null, null)).isEqualTo("en");
    }

    @Test
    void weightsDominantLanguageByRawTextLength() {
        Course course = new Course("Course");
        Document shortGerman = new Document(course, "de.pdf", "application/pdf", "kurz");
        shortGerman.setLanguage("de");
        Document longEnglish = new Document(course, "en.pdf", "application/pdf", "english ".repeat(10));
        longEnglish.setLanguage("en");

        assertThat(LanguageUtils.dominantLanguage(List.of(shortGerman, longEnglish))).isEqualTo("en");
    }

    @Test
    void usesRoleBeforeBloomAndKeepsBloomFallbackForLegacyGoals() {
        Course course = new Course("Course");
        LearningGoal taggedKnowledge = new LearningGoal(course, "Explain the method.", GoalKind.IMPLICIT);
        taggedKnowledge.setRole(GoalRole.KNOWLEDGE);
        taggedKnowledge.setBloomLevel(BloomLevel.APPLY);
        LearningGoal taggedSkill = new LearningGoal(course, "Know the method.", GoalKind.IMPLICIT);
        taggedSkill.setRole(GoalRole.SKILL);
        taggedSkill.setBloomLevel(BloomLevel.UNDERSTAND);
        LearningGoal legacySkill = new LearningGoal(course, "Apply the method.", GoalKind.IMPLICIT);
        legacySkill.setBloomLevel(BloomLevel.APPLY);

        assertThat(TopicTreeService.isSkillTier(taggedKnowledge)).isFalse();
        assertThat(TopicTreeService.isSkillTier(taggedSkill)).isTrue();
        assertThat(TopicTreeService.isSkillTier(legacySkill)).isTrue();
    }
}

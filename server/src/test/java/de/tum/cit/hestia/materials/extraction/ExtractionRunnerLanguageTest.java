package de.tum.cit.hestia.materials.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.topictree.TopicTreeService;
import de.tum.cit.hestia.materials.course.CourseContext;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.LanguageUtils;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.DomainModule;
import java.util.UUID;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionRunnerLanguageTest {

    @Test
    void resolvesCourseOverrideBeforeDocumentDominantAndFallback() {
        CourseContext course = new CourseContext(UUID.randomUUID(), "Course", "de", false);

        assertThat(LanguageUtils.resolveLanguage(course, "en", "fr")).isEqualTo("de");
    }

    @Test
    void resolvesDocumentLanguageBeforeDominantAndFallback() {
        CourseContext course = new CourseContext(UUID.randomUUID(), "Course", null, false);

        assertThat(LanguageUtils.resolveLanguage(course, "de", "en")).isEqualTo("de");
    }

    @Test
    void resolvesDominantLanguageBeforeEnglishFallback() {
        CourseContext course = new CourseContext(UUID.randomUUID(), "Course", null, false);

        assertThat(LanguageUtils.resolveLanguage(course, null, "de")).isEqualTo("de");
        assertThat(LanguageUtils.resolveLanguage(course, null, null)).isEqualTo("en");
    }

    @Test
    void weightsDominantLanguageByRawTextLength() {
        CourseContext course = new CourseContext(UUID.randomUUID(), "Course", null, false);
        Document shortGerman = new Document(course.id(), "de.pdf", "application/pdf", "kurz");
        shortGerman.setLanguage("de");
        Document longEnglish = new Document(course.id(), "en.pdf", "application/pdf", "english ".repeat(10));
        longEnglish.setLanguage("en");

        assertThat(LanguageUtils.dominantLanguage(List.of(shortGerman, longEnglish))).isEqualTo("en");
    }

    @Test
    void usesRoleBeforeBloomAndKeepsBloomFallbackForLegacyGoals() {
        PlacedGoal taggedKnowledge = goal(GoalRole.KNOWLEDGE, BloomLevel.APPLY);
        PlacedGoal taggedSkill = goal(GoalRole.SKILL, BloomLevel.UNDERSTAND);
        PlacedGoal legacySkill = goal(null, BloomLevel.APPLY);

        assertThat(TopicTreeService.isSkillTier(taggedKnowledge)).isFalse();
        assertThat(TopicTreeService.isSkillTier(taggedSkill)).isTrue();
        assertThat(TopicTreeService.isSkillTier(legacySkill)).isTrue();
    }

    private static PlacedGoal goal(GoalRole role, BloomLevel bloom) {
        return new PlacedGoal(new GoalInfo(UUID.randomUUID(), UUID.randomUUID(), 1, "Goal", "Goal", null,
                GoalKind.IMPLICIT, role, bloom.name(), null, GoalStatus.PENDING, GoalOrigin.EXTRACTED, null,
                DomainModule.MATERIALS, null, java.time.Instant.now(), null), null);
    }
}

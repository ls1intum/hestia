package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.extraction.SourceMatchQuality;
import org.junit.jupiter.api.Test;

class GoalSourceTest {

    private final LearningGoal goal = new LearningGoal(null, "Apply test-driven development.", GoalKind.EXPLICIT);
    private final Document document = new Document(null, "lecture.pdf", "application/pdf", "lecture text");

    @Test
    void noneDemotesEvidenceAndRetainsRejectedSnippet() {
        GoalSource source = new GoalSource(goal, document, "fabricated heading and bullet", 4, SourceMatchQuality.NONE);

        assertThat(source.getSnippet()).isEmpty();
        assertThat(source.getPage()).isNull();
        assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.NONE);
        assertThat(source.getEvidenceKind()).isEqualTo(EvidenceKind.UNSUPPORTED);
        assertThat(source.isGrounded()).isFalse();
        assertThat(source.getUnverifiedSnippet()).isEqualTo("fabricated heading and bullet");
    }

    @Test
    void matchedQualityKeepsEvidenceAndDoesNotRetainUnverifiedSnippet() {
        GoalSource exact = new GoalSource(goal, document, "verbatim quote", 4, SourceMatchQuality.EXACT_IN_SESSION);
        GoalSource fragment = new GoalSource(goal, document, "verbatim fragment", 5, SourceMatchQuality.FRAGMENT);

        assertThat(exact.getSnippet()).isEqualTo("verbatim quote");
        assertThat(exact.getPage()).isEqualTo(4);
        assertThat(exact.getEvidenceKind()).isEqualTo(EvidenceKind.TEXT);
        assertThat(exact.getUnverifiedSnippet()).isNull();
        assertThat(fragment.getSnippet()).isEqualTo("verbatim fragment");
        assertThat(fragment.getPage()).isEqualTo(5);
        assertThat(fragment.getEvidenceKind()).isEqualTo(EvidenceKind.TEXT);
        assertThat(fragment.getUnverifiedSnippet()).isNull();
    }

    @Test
    void blankOrNullNoneSnippetHasNoUnverifiedText() {
        GoalSource blank = new GoalSource(goal, document, "  ", 4, SourceMatchQuality.NONE);
        GoalSource missing = new GoalSource(goal, document, null, 4, SourceMatchQuality.NONE);

        assertThat(blank.getSnippet()).isEmpty();
        assertThat(blank.getPage()).isNull();
        assertThat(blank.getUnverifiedSnippet()).isNull();
        assertThat(missing.getSnippet()).isEmpty();
        assertThat(missing.getPage()).isNull();
        assertThat(missing.getUnverifiedSnippet()).isNull();
    }

    @Test
    void legacyConstructorsKeepTheirExistingValues() {
        GoalSource snippetOnly = new GoalSource(goal, document, "manual quote");
        GoalSource withPage = new GoalSource(goal, document, "manual page quote", 7);
        GoalSource withGroundedFlag = new GoalSource(goal, document, "manual grounded quote", 8, true);
        GoalSource unknownQuality = new GoalSource(goal, document, "unknown quote", 9, null);

        assertThat(snippetOnly.getSnippet()).isEqualTo("manual quote");
        assertThat(snippetOnly.getPage()).isNull();
        assertThat(snippetOnly.isGrounded()).isFalse();
        assertThat(snippetOnly.getGroundingQuality()).isNull();
        assertThat(withPage.getSnippet()).isEqualTo("manual page quote");
        assertThat(withPage.getPage()).isEqualTo(7);
        assertThat(withGroundedFlag.getSnippet()).isEqualTo("manual grounded quote");
        assertThat(withGroundedFlag.getPage()).isEqualTo(8);
        assertThat(withGroundedFlag.isGrounded()).isTrue();
        assertThat(unknownQuality.getSnippet()).isEqualTo("unknown quote");
        assertThat(unknownQuality.getPage()).isEqualTo(9);
        assertThat(unknownQuality.isGrounded()).isFalse();
        assertThat(unknownQuality.getGroundingQuality()).isNull();
        assertThat(snippetOnly.getEvidenceKind()).isEqualTo(EvidenceKind.UNSUPPORTED);
        assertThat(withGroundedFlag.getEvidenceKind()).isEqualTo(EvidenceKind.TEXT);
        assertThat(snippetOnly.getUnverifiedSnippet()).isNull();
        assertThat(withPage.getUnverifiedSnippet()).isNull();
        assertThat(withGroundedFlag.getUnverifiedSnippet()).isNull();
        assertThat(unknownQuality.getUnverifiedSnippet()).isNull();
    }

    @Test
    void figureSourceKeepsPageWithoutPresentingVerbatimEvidence() {
        GoalSource source = GoalSource.figure(goal, document, 7);

        assertThat(source.isGrounded()).isFalse();
        assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.NONE);
        assertThat(source.getEvidenceKind()).isEqualTo(EvidenceKind.FIGURE);
        assertThat(source.getPage()).isEqualTo(7);
        assertThat(source.getSnippet()).isEmpty();
        assertThat(source.getUnverifiedSnippet()).isNull();
    }
}

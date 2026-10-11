package app.shared;

import app.error.ApiException;
import app.examination.Examination;
import app.examination.ExaminationRepository;
import app.section.SectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Access is the single choke-point every controller funnels through for
 * ownership. It must return the exam only to its owner, hide existence from
 * everyone else (404 vs 403 semantics), and never 500 on a malformed id.
 */
class AccessTest {

    private final ExaminationRepository exams = mock(ExaminationRepository.class);
    private final SectionRepository sections = mock(SectionRepository.class);
    private final Access access = new Access(exams, sections);

    private static Examination ownedBy(UUID owner) {
        Examination e = new Examination();
        e.setOwnerId(owner);
        return e;
    }

    @Test
    void returnsExaminationToItsOwner() {
        UUID owner = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        Examination exam = ownedBy(owner);
        when(exams.findById(examId)).thenReturn(Optional.of(exam));

        assertThat(access.requireExamination(examId, owner.toString())).isSameAs(exam);
    }

    @Test
    void forbidsAccessToAnotherUsersExamination() {
        UUID examId = UUID.randomUUID();
        when(exams.findById(examId)).thenReturn(Optional.of(ownedBy(UUID.randomUUID())));

        assertThatThrownBy(() -> access.requireExamination(examId, UUID.randomUUID().toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unknownExaminationIs404() {
        UUID examId = UUID.randomUUID();
        when(exams.findById(examId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireExamination(examId, UUID.randomUUID().toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void malformedPathIdMapsTo404NotServerError() {
        assertThatThrownBy(() -> Access.id("not-a-uuid"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void wellFormedIdParses() {
        UUID id = UUID.randomUUID();
        assertThat(Access.id(id.toString())).isEqualTo(id);
    }
}

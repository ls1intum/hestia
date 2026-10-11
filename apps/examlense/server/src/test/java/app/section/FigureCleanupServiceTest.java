package app.section;

import app.storage.StorageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FigureCleanupServiceTest {

    @BeforeEach
    void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void storageCleanupWaitsForCommitAndSwallowsFailures() {
        SectionFigureRepository figures = mock(SectionFigureRepository.class);
        StorageService storage = mock(StorageService.class);
        FigureCleanupService cleanup = new FigureCleanupService(figures, storage);
        UUID examId = UUID.randomUUID();
        String path = "owner/" + examId + "/figure.png";
        when(figures.findStoragePathsByExamId(examId)).thenReturn(List.of(path));
        doThrow(new RuntimeException("storage unavailable"))
            .when(storage).delete("exam-figures", path);

        cleanup.scheduleForExam(examId);
        verifyNoInteractions(storage);

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCommit()))
            .doesNotThrowAnyException();
        verify(storage).delete("exam-figures", path);
    }
}

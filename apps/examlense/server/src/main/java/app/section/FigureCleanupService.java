package app.section;

import app.storage.StorageService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps stored figure objects aligned with transactionally deleted figure rows. */
@Service
public class FigureCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FigureCleanupService.class);
    private static final String FIGURE_BUCKET = "exam-figures";

    private final SectionFigureRepository figures;
    private final StorageService storage;

    public FigureCleanupService(SectionFigureRepository figures, StorageService storage) {
        this.figures = figures;
        this.storage = storage;
    }

    @Transactional
    public void deleteFigure(SectionFigure figure) {
        schedulePaths(List.of(figure.getStoragePath()));
        figures.delete(figure);
    }

    public void scheduleForBlock(UUID blockId) {
        schedulePaths(figures.findByBlockIdOrderByPositionAsc(blockId).stream()
            .map(SectionFigure::getStoragePath).toList());
    }

    public void scheduleForSection(UUID examId, UUID sectionId) {
        schedulePaths(figures.findStoragePathsBySection(examId, sectionId));
    }

    public void scheduleForExam(UUID examId) {
        schedulePaths(figures.findStoragePathsByExamId(examId));
    }

    private void schedulePaths(Collection<String> rawPaths) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String path : rawPaths) {
            if (path != null && !path.isBlank()) paths.add(path);
        }
        if (paths.isEmpty()) return;

        Runnable cleanup = () -> deleteBestEffort(paths);
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    private void deleteBestEffort(Collection<String> paths) {
        for (String path : paths) {
            try {
                storage.delete(FIGURE_BUCKET, path);
            } catch (RuntimeException e) {
                log.warn("Failed to delete figure object {}/{} after its row was removed",
                    FIGURE_BUCKET, path, e);
            }
        }
    }
}

package app.api;

import app.error.ApiException;
import app.persistence.entity.Exam;
import app.persistence.repository.ExamRepository;
import app.security.CurrentUser;
import app.storage.StorageService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exams")
public class ExamController {

    public record CreateExamRequest(
        String title, String course, String semester, String instructor_name,
        String language, String source, String status, String source_file_url,
        String parser_model, String solver_model, Long lgh_course_id
    ) {}

    private final ExamRepository examRepository;
    private final Access access;
    private final CrudService crud;
    private final StorageService storage;
    private final ExamProgressService progress;

    public ExamController(ExamRepository examRepository, Access access, CrudService crud,
                          StorageService storage, ExamProgressService progress) {
        this.examRepository = examRepository;
        this.access = access;
        this.crud = crud;
        this.storage = storage;
        this.progress = progress;
    }

    @GetMapping
    public List<Dtos.ExamListItemDto> list(@CurrentUser String userId) {
        List<Exam> exams = examRepository.findByOwnerIdOrderByCreatedAtDesc(UUID.fromString(userId));
        var counts = progress.countsFor(exams.stream().map(Exam::getId).toList());
        return exams.stream()
            .map(e -> Dtos.ExamListItemDto.from(e,
                counts.getOrDefault(e.getId(), ExamProgressService.Counts.EMPTY)))
            .toList();
    }

    @GetMapping("/{id}")
    public Dtos.ExamDto get(@PathVariable String id, @CurrentUser String userId) {
        return Dtos.ExamDto.from(access.requireExam(Access.id(id), userId));
    }

    @PostMapping
    public Dtos.ExamDto create(@RequestBody CreateExamRequest req, @CurrentUser String userId) {
        Exam e = new Exam();
        e.setOwnerId(UUID.fromString(userId));
        if (req.title() != null) e.setTitle(req.title());
        if (req.course() != null) e.setCourse(req.course());
        if (req.semester() != null) e.setSemester(req.semester());
        if (req.instructor_name() != null) e.setInstructorName(req.instructor_name());
        if (req.language() != null) e.setLanguage(req.language());
        if (req.source() != null) e.setSource(req.source());
        if (req.status() != null) e.setStatus(req.status());
        if (req.source_file_url() != null) e.setSourceFileUrl(req.source_file_url());
        if (req.parser_model() != null) e.setParserModel(req.parser_model());
        if (req.solver_model() != null) e.setSolverModel(req.solver_model());
        if (req.lgh_course_id() != null) e.setLghCourseId(req.lgh_course_id());
        return Dtos.ExamDto.from(examRepository.save(e));
    }

    /** Statuses the client may set directly (mirrors the DB check constraint). */
    private static final Set<String> PATCHABLE_STATUSES =
        Set.of("draft", "parsing", "failed", "ready", "evaluating", "grading", "finished");

    @PatchMapping("/{id}")
    public Dtos.ExamDto patch(@PathVariable String id, @RequestBody Map<String, Object> body,
                              @CurrentUser String userId) {
        Exam e = access.requireExam(Access.id(id), userId);
        if (Patch.has(body, "title")) e.setTitle(Patch.str(body.get("title")));
        if (Patch.has(body, "course")) e.setCourse(Patch.str(body.get("course")));
        if (Patch.has(body, "semester")) e.setSemester(Patch.str(body.get("semester")));
        if (Patch.has(body, "instructor_name")) e.setInstructorName(Patch.str(body.get("instructor_name")));
        if (Patch.has(body, "total_points")) e.setTotalPoints(Patch.bigDecimal(body.get("total_points")));
        if (Patch.has(body, "language")) e.setLanguage(Patch.str(body.get("language")));
        // status/parse_error stay client-writable — the frontend's retry-parse,
        // cancel-recovery, and finish-grading flows patch them — but the status
        // value is validated so a bad one is a 400, not an opaque constraint 500.
        // parse_phase is internal progress state and deliberately NOT patchable.
        if (Patch.has(body, "status")) {
            String status = Patch.str(body.get("status"));
            if (status == null || !PATCHABLE_STATUSES.contains(status)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid status: " + status);
            }
            e.setStatus(status);
        }
        if (Patch.has(body, "parse_error")) e.setParseError(Patch.str(body.get("parse_error")));
        if (Patch.has(body, "parser_model")) e.setParserModel(Patch.str(body.get("parser_model")));
        if (Patch.has(body, "solver_model")) e.setSolverModel(Patch.str(body.get("solver_model")));
        if (Patch.has(body, "source_file_url")) e.setSourceFileUrl(Patch.str(body.get("source_file_url")));
        if (Patch.has(body, "lgh_course_id")) e.setLghCourseId(Patch.longVal(body.get("lgh_course_id")));
        return Dtos.ExamDto.from(examRepository.save(e));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        Exam e = access.requireExam(Access.id(id), userId);
        // Best-effort storage cleanup: the source PDF and every figure object
        // under this exam's prefix. DB children (sections/tasks/blocks/figures,
        // answers/grades) cascade via FKs.
        if (e.getSourceFileUrl() != null && !e.getSourceFileUrl().isBlank()) {
            try { storage.delete("exam-pdfs", e.getSourceFileUrl()); } catch (RuntimeException ignored) {}
        }
        try { storage.deletePrefix("exam-figures", userId + "/" + e.getId()); } catch (RuntimeException ignored) {}
        examRepository.delete(e);
        return ResponseEntity.noContent().build();
    }

    /**
     * Cancel an in-progress parse/evaluate and revert the exam to {@code failed}.
     * The background job is fire-and-forget and cannot be interrupted, but it
     * re-checks the exam status before writing results, so flipping to failed here
     * stops a late-returning LLM response from resurrecting the exam. Returns 409
     * if the exam is not currently processing.
     */
    @PostMapping("/{id}/cancel")
    public Dtos.ExamDto cancel(@PathVariable String id, @CurrentUser String userId) {
        Exam e = access.requireExam(Access.id(id), userId); // 404 if missing, 403 if not owner
        int updated = examRepository.cancelProcessing(e.getId(), "Cancelled.");
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Exam is not currently processing");
        }
        return Dtos.ExamDto.from(examRepository.findById(e.getId()).orElse(e));
    }

    @PostMapping("/{id}/duplicate")
    public Dtos.ExamDto duplicate(@PathVariable String id, @CurrentUser String userId) {
        Exam src = access.requireExam(Access.id(id), userId);
        return Dtos.ExamDto.from(crud.duplicateExam(src, userId));
    }
}

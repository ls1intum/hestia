package de.tum.cit.hestia.assessment.parse;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.ConsentRequiredException;
import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmJobs;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.assessment.ai.StrategyModels;
import de.tum.cit.hestia.assessment.ai.ParserStrategies;
import de.tum.cit.hestia.assessment.ai.ParserStrategy;
import de.tum.cit.hestia.assessment.ai.ProviderKind;
import de.tum.cit.hestia.assessment.shared.Access;
import de.tum.cit.hestia.assessment.error.ApiException;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.parse.figures.FigureExtractionService;
import de.tum.cit.hestia.assessment.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of supabase/functions/parse-exam-pdf. Orchestrates the extraction
 * pipeline: download → build LLM input ({@link ParseInputBuilder}) → LLM
 * extract → persist rows ({@link ParsedExaminationPersister}) → finalize status.
 * Progress/failure reporting lives in {@link ParseProgress}.
 *
 * Access is verified up-front in {@link #preflight}. The actual work runs on
 * the `solverExecutor` pool so the HTTP POST returns immediately (202).
 */
@Service
public class ParseExaminationService {

    private static final Logger log = LoggerFactory.getLogger(ParseExaminationService.class);
    static final String MODULE = "assessment";
    static final String PURPOSE = "exam-parse";
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    /**
     * Hard page cap for every mode. The rasterize path holds one PNG per page
     * in memory and base64-inflates them ×1.33 into a single request — a
     * several-hundred-page PDF would OOM the pool or trip provider limits.
     */
    private static final int MAX_PAGES = 100;

    private final ExaminationRepository examRepository;
    private final StorageService storage;
    private final LlmGateway gateway;
    private final PdfPageCounter pageCounter;
    private final ParseInputBuilder inputBuilder;
    private final ParsedExaminationPersister persister;
    private final ParseMetricsRecorder metricsRecorder;
    private final ParseProgress progress;
    private final FigureExtractionService figureExtraction;
    private final LlmJobs jobs;
    private final Access access;

    public ParseExaminationService(
        ExaminationRepository examRepository,
        StorageService storage,
        LlmGateway gateway,
        PdfPageCounter pageCounter,
        ParseInputBuilder inputBuilder,
        ParsedExaminationPersister persister,
        ParseMetricsRecorder metricsRecorder,
        ParseProgress progress,
        FigureExtractionService figureExtraction,
        LlmJobs jobs,
        Access access
    ) {
        this.access = access;
        this.examRepository = examRepository;
        this.storage = storage;
        this.gateway = gateway;
        this.pageCounter = pageCounter;
        this.inputBuilder = inputBuilder;
        this.persister = persister;
        this.metricsRecorder = metricsRecorder;
        this.progress = progress;
        this.figureExtraction = figureExtraction;
        this.jobs = jobs;
    }

    /**
     * Synchronously verifies ownership + flips the exam to `parsing`, then the
     * controller kicks off the async background work. Throws ApiException on
     * pre-flight failures so the controller can map them to HTTP responses.
     *
     * Note: the exam is often ALREADY in `parsing` here — the frontend sets that
     * status when it creates the exam (and again on retry) before calling this —
     * so this flip is unconditional rather than a compare-and-set. Protection
     * against a re-parse producing duplicate rows lives where it belongs: the
     * persister is transactional and clears prior parse artifacts before insert.
     */
    public Preflight preflight(String examId, String parserModelId) {
        UUID examUuid = Access.id(examId);
        Examination exam = access.requireExaminationForWrite(examUuid);
        ParserStrategy strategy = ParserStrategies.resolve(parserModelId);
        // Only a re-parse of an old exam can name a withdrawn model — new ones pick
        // from the catalog. Refuse here rather than in the background run: the user
        // gets an answer immediately, and we don't rasterize a PDF for a call that
        // cannot be made.
        if (strategy.providerKind() == ProviderKind.RETIRED) {
            throw new ApiException(HttpStatus.GONE, ParseErrorMessages.AI_MODEL_RETIRED);
        }

        // Admit the job here: this is the last synchronous point before the PDF is
        // rasterized and dispatched, and once queued the spend is committed. It checks
        // the caller's limit and, for an external model, their consent (HTTP 428 so the
        // client can ask and retry). Deliberately after the ownership and retired-model
        // checks so neither of those costs anyone a run, and before anything changes.
        UUID jobId = jobs.admit(MODULE, PURPOSE, exam.getCourseId(), StrategyModels.of(strategy));

        exam.setStatus("parsing");
        exam.setParseError(null);
        exam.setParserModel(strategy.id());
        // Anchor the client's progress countdown to *this* attempt (initial or
        // retry), not the exam's original created_at.
        exam.setParseStartedAt(OffsetDateTime.now());
        examRepository.save(exam);
        progress.notifyExamination(examUuid);
        return new Preflight(strategy, jobId);
    }

    /** What the controller needs to start the run: the resolved parser and the admitted job. */
    public record Preflight(ParserStrategy strategy, UUID jobId) {}

    @Async("solverExecutor")
    public void runAsync(
        String examId,
        String userId,
        String storagePath,
        ParserStrategy strategy,
        UUID jobId,
        long requestNanos
    ) {
        // Valid by construction: preflight already resolved this exam.
        UUID examUuid = UUID.fromString(examId);
        long pipelineStart = System.nanoTime();
        ParserStrategy.PdfMode pdfMode = strategy.pdfMode();
        log.info("parse-exam-pdf[{}] timing start strategy={} mode={} queueWait={}ms",
            examId, strategy.id(), pdfMode, msSince(requestNanos));

        ParseAttempt attempt = new ParseAttempt(examUuid, userId, strategy.id(), pdfMode.name());
        attempt.jobId = jobId;
        try {
            run(attempt, strategy, pdfMode, storagePath);
            log.info("parse-exam-pdf[{}] timing total took={}ms strategy={}",
                examId, msSince(pipelineStart), strategy.id());
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] uncaught", examId, e);
            fail(attempt, ParseErrorMessages.UNEXPECTED);
        } finally {
            // Pipeline-only: measured from dequeue (pipelineStart), so preflight +
            // queue wait are excluded. This isolates model/pipeline performance from
            // scheduling delay. The "queueWait" log line above still reports the wait
            // separately if e2e timing is needed.
            attempt.durationMs = msSince(pipelineStart);
            metricsRecorder.record(attempt);
        }
    }

    private void run(ParseAttempt attempt, ParserStrategy strategy, ParserStrategy.PdfMode pdfMode,
                     String storagePath) {
        UUID examId = attempt.examId;

        // 1. Download + sanity checks
        progress.setPhase(examId, "downloading");
        long tDownload = System.nanoTime();
        byte[] bytes;
        try {
            bytes = storage.download("exam-pdfs", storagePath);
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] storage download failed", examId, e);
            fail(attempt, ParseErrorMessages.PDF_OPEN_FAILED);
            return;
        }
        if (bytes == null) {
            fail(attempt, ParseErrorMessages.PDF_OPEN_FAILED);
            return;
        }
        if (bytes.length > MAX_BYTES) {
            fail(attempt, ParseErrorMessages.PDF_TOO_LARGE);
            return;
        }
        attempt.pageCount = pageCounter.pageCount(bytes);
        if (attempt.pageCount == null || attempt.pageCount == 0) {
            fail(attempt, ParseErrorMessages.PDF_INVALID);
            return;
        }
        if (attempt.pageCount > MAX_PAGES) {
            fail(attempt, ParseErrorMessages.tooManyPages(attempt.pageCount, MAX_PAGES));
            return;
        }
        log.info("parse-exam-pdf[{}] timing step=download took={}ms bytes={}",
            examId, msSince(tDownload), bytes.length);

        // 2. Build user content depending on PDF mode
        LlmContent.UserContent userContent;
        try {
            userContent = inputBuilder.build(pdfMode, examId, bytes);
        } catch (ParseInputBuilder.InputException e) {
            fail(attempt, e.getMessage());
            return;
        }

        // 3. LLM call. Provider construction is inside the try so a missing API key
        // (thrown as ProviderException with status 500) maps to a friendly message too.
        // On a transient failure of the primary parser the pipeline falls back once to
        // a different model (see extractWithFallback) before surfacing an error.
        Map<String, Object> parsed;
        try {
            parsed = extractWithFallback(attempt, strategy, userContent, pdfMode, bytes);
        } catch (ConsentRequiredException e) {
            // Withdrawn while the run was queued or running: not transient, nothing was sent.
            fail(attempt, ParseErrorMessages.CONSENT_WITHDRAWN);
            return;
        } catch (AiExceptions.RateLimitException e) {
            fail(attempt, ParseErrorMessages.AI_RATE_LIMIT);
            return;
        } catch (AiExceptions.PaymentRequiredException e) {
            fail(attempt, ParseErrorMessages.AI_OUT_OF_CREDITS);
            return;
        } catch (AiExceptions.MalformedModelOutputException e) {
            log.error("parse-exam-pdf[{}] malformed model output", examId, e);
            fail(attempt, ParseErrorMessages.NOT_STRUCTURED);
            return;
        } catch (AiExceptions.ProviderException e) {
            log.error("parse-exam-pdf[{}] provider error (status {})", examId, e.status(), e);
            fail(attempt, ParseErrorMessages.forProviderStatus(e.status()));
            return;
        }

        if (parsed == null || parsed.isEmpty()) {
            fail(attempt, ParseErrorMessages.NOT_STRUCTURED);
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) parsed.get("tasks");
        if (tasks == null || tasks.isEmpty()) {
            fail(attempt, ParseErrorMessages.NO_TASKS);
            return;
        }

        // A fallback served the parse: reflect the model that actually parsed on the
        // exam so the editor/admin show it (preflight stamped the primary). Best-effort.
        if (!attempt.parserModel.equals(strategy.id())) {
            progress.setParserModel(examId, attempt.parserModel);
        }

        // 4. Persist
        progress.setPhase(examId, "persisting");
        long tPersist = System.nanoTime();
        ParsedExaminationPersister.PersistResult persisted = persister.persist(attempt, parsed, tasks);
        attempt.success = persisted.ok();
        log.info("parse-exam-pdf[{}] timing step=persist took={}ms tasks={}",
            examId, msSince(tPersist), tasks.size());

        // 5. Fill the figure placeholders with crops from the PDF. Dispatched, not
        // inlined: the exam is already `draft` and usable, and this must never be
        // able to fail a parse that produced tasks. Unlike the LLM steps above it
        // costs nothing per run — the geometry is read from the PDF itself, not
        // from whatever input the parser was shown.
        if (persisted.ok() && !persisted.figureBlocks().isEmpty()) {
            figureExtraction.extractAsync(
                examId, storagePath, persisted.figureBlocks());
        }
    }

    /**
     * Runs the parser LLM call for {@code primary}; on a <em>transient</em> failure
     * (busy/unreachable/5xx — see {@link AiExceptions#isTransient}) or when the primary
     * provider's account is out of credits (402), retries once with the
     * {@link ParserStrategies#FALLBACK_ID} model of another provider. Other failures
     * (malformed output, retired model) and a failed fallback propagate to the
     * caller's exception→message mapping. Updates {@code attempt} to record the model that
     * actually served so the single metrics row and the exam reflect it.
     */
    private Map<String, Object> extractWithFallback(
        ParseAttempt attempt, ParserStrategy primary, LlmContent.UserContent primaryContent,
        ParserStrategy.PdfMode primaryMode, byte[] bytes
    ) {
        try {
            return extract(attempt, primary, primaryContent);
        } catch (RuntimeException e) {
            ParserStrategy fallback = ParserStrategies.resolve(ParserStrategies.FALLBACK_ID);
            // An exhausted account is not transient, but it is the primary provider's problem
            // only: the fallback runs on another provider's account.
            boolean worthFallback = AiExceptions.isTransient(e) || e instanceof AiExceptions.PaymentRequiredException;
            if (!worthFallback || fallback.id().equals(primary.id())) {
                throw e;
            }
            log.warn("parse-exam-pdf[{}] primary parser {} failed ({}); falling back to {}",
                attempt.examId, primary.id(), e.toString(), fallback.id());
            // Only rebuild input if the mode actually differs (the default→fallback
            // pair shares PDF_DIRECT, so this reuses the content already built).
            ParserStrategy.PdfMode fbMode = fallback.pdfMode();
            LlmContent.UserContent fbContent = fbMode == primaryMode
                ? primaryContent
                : inputBuilder.build(fbMode, attempt.examId, bytes);
            attempt.parserModel = fallback.id();
            attempt.pdfMode = fbMode.name();
            return extract(attempt, fallback, fbContent);
        }
    }

    /** One model call for the given strategy; stamps llm timing + usage onto the attempt. */
    private Map<String, Object> extract(ParseAttempt attempt, ParserStrategy strategy, LlmContent.UserContent userContent) {
        // Refuses a retired model (410, non-transient) before anything is sent.
        ModelRef model = StrategyModels.of(strategy);
        progress.setPhase(attempt.examId, "extracting");
        long tLlm = System.nanoTime();
        LlmResponse res = gateway.chat(LlmRequest.of(MODULE, PURPOSE, userContent)
            .withModel(model)
            .withSystem(ParseExaminationPrompts.SYSTEM_PROMPT)
            .withTool(new LlmContent.Tool(
                ParseExaminationPrompts.TOOL_NAME,
                ParseExaminationPrompts.TOOL_DESCRIPTION,
                ParseExaminationPrompts.schema()))
            .inJob(attempt.jobId));
        attempt.llmMs = msSince(tLlm);
        attempt.usage = res.usage();
        log.info("parse-exam-pdf[{}] timing step=llm-call took={}ms model={}",
            attempt.examId, attempt.llmMs, res.model());
        return res.toolArgs();
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Stamps the error onto the attempt (recorded later) then flips the exam to failed. */
    private void fail(ParseAttempt attempt, String message) {
        attempt.error = message;
        progress.fail(attempt.examId, message);
    }
}

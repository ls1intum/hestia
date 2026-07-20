package app.parse;

import app.ai.AiExceptions;
import app.ai.AiProvider;
import app.ai.AiProviderFactory;
import app.ai.ParserStrategies;
import app.ai.ParserStrategy;
import app.shared.Access;
import app.error.ApiException;
import app.exam.Exam;
import app.exam.ExamRepository;
import app.storage.StorageService;
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
 * extract → persist rows ({@link ParsedExamPersister}) → finalize status.
 * Progress/failure reporting lives in {@link ParseProgress}.
 *
 * Ownership is verified up-front in the controller. The actual work runs on
 * the `solverExecutor` pool so the HTTP POST returns immediately (202).
 */
@Service
public class ParseExamService {

    private static final Logger log = LoggerFactory.getLogger(ParseExamService.class);
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    /**
     * Hard page cap for every mode. The rasterize path holds one PNG per page
     * in memory and base64-inflates them ×1.33 into a single request — a
     * several-hundred-page PDF would OOM the pool or trip provider limits.
     */
    private static final int MAX_PAGES = 100;

    private final ExamRepository examRepository;
    private final StorageService storage;
    private final AiProviderFactory providerFactory;
    private final PdfTextExtractor textExtractor;
    private final ParseInputBuilder inputBuilder;
    private final ParsedExamPersister persister;
    private final ParseMetricsRecorder metricsRecorder;
    private final ParseProgress progress;

    public ParseExamService(
        ExamRepository examRepository,
        StorageService storage,
        AiProviderFactory providerFactory,
        PdfTextExtractor textExtractor,
        ParseInputBuilder inputBuilder,
        ParsedExamPersister persister,
        ParseMetricsRecorder metricsRecorder,
        ParseProgress progress
    ) {
        this.examRepository = examRepository;
        this.storage = storage;
        this.providerFactory = providerFactory;
        this.textExtractor = textExtractor;
        this.inputBuilder = inputBuilder;
        this.persister = persister;
        this.metricsRecorder = metricsRecorder;
        this.progress = progress;
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
    public ParserStrategy preflight(String examId, String userId, String parserModelId) {
        UUID examUuid = Access.id(examId);
        Exam exam = examRepository.findById(examUuid)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));
        if (!exam.getOwnerId().toString().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        ParserStrategy strategy = ParserStrategies.resolve(parserModelId);

        exam.setStatus("parsing");
        exam.setParseError(null);
        exam.setParserModel(strategy.id());
        // Anchor the client's progress countdown to *this* attempt (initial or
        // retry), not the exam's original created_at.
        exam.setParseStartedAt(OffsetDateTime.now());
        examRepository.save(exam);
        progress.notifyExam(examUuid);
        return strategy;
    }

    @Async("solverExecutor")
    public void runAsync(
        String examId,
        String userId,
        String storagePath,
        String languageHint,
        ParserStrategy strategy,
        boolean fastMode,
        long requestNanos
    ) {
        // Valid by construction: preflight already resolved this exam.
        UUID examUuid = UUID.fromString(examId);
        long pipelineStart = System.nanoTime();
        ParserStrategy.PdfMode pdfMode = effectivePdfMode(strategy, fastMode);
        log.info("parse-exam-pdf[{}] timing start strategy={} mode={} fastMode={} queueWait={}ms",
            examId, strategy.id(), pdfMode, fastMode, msSince(requestNanos));

        ParseAttempt attempt = new ParseAttempt(examUuid, userId, strategy.id(), pdfMode.name());
        try {
            run(attempt, strategy, pdfMode, fastMode, storagePath, languageHint);
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
                     boolean fastMode, String storagePath, String languageHint) {
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
        attempt.pageCount = textExtractor.pageCount(bytes);
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
        AiProvider.UserContent userContent;
        try {
            userContent = inputBuilder.build(pdfMode, examId, bytes, languageHint);
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
            parsed = extractWithFallback(attempt, strategy, userContent, pdfMode, fastMode, bytes, languageHint);
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
        attempt.success = persister.persist(attempt, parsed, tasks, languageHint);
        log.info("parse-exam-pdf[{}] timing step=persist took={}ms tasks={}",
            examId, msSince(tPersist), tasks.size());
    }

    /**
     * Runs the parser LLM call for {@code primary}; on a <em>transient</em> failure
     * (busy/unreachable/5xx — see {@link AiExceptions#isTransient}) retries once with
     * the {@link ParserStrategies#FALLBACK_ID} model, honoring fast mode. Non-transient
     * failures (malformed output, out-of-credits) and a failed fallback propagate to the
     * caller's exception→message mapping. Updates {@code attempt} to record the model that
     * actually served so the single metrics row and the exam reflect it.
     */
    private Map<String, Object> extractWithFallback(
        ParseAttempt attempt, ParserStrategy primary, AiProvider.UserContent primaryContent,
        ParserStrategy.PdfMode primaryMode, boolean fastMode, byte[] bytes, String languageHint
    ) {
        try {
            return extract(attempt, primary, primaryContent);
        } catch (RuntimeException e) {
            ParserStrategy fallback = ParserStrategies.resolve(ParserStrategies.FALLBACK_ID);
            if (!AiExceptions.isTransient(e) || fallback.id().equals(primary.id())) {
                throw e;
            }
            log.warn("parse-exam-pdf[{}] primary parser {} failed transiently ({}); falling back to {}",
                attempt.examId, primary.id(), e.toString(), fallback.id());
            // Honor fast mode for the fallback; only rebuild input if the mode actually
            // differs (the default→fallback pair shares PDF_DIRECT, so this reuses content).
            ParserStrategy.PdfMode fbMode = effectivePdfMode(fallback, fastMode);
            AiProvider.UserContent fbContent = fbMode == primaryMode
                ? primaryContent
                : inputBuilder.build(fbMode, attempt.examId, bytes, languageHint);
            attempt.parserModel = fallback.id();
            attempt.pdfMode = fbMode.name();
            return extract(attempt, fallback, fbContent);
        }
    }

    /** One provider call for the given strategy; stamps llm timing + usage onto the attempt. */
    private Map<String, Object> extract(ParseAttempt attempt, ParserStrategy strategy, AiProvider.UserContent userContent) {
        AiProvider provider = providerFactory.forParser(strategy);
        progress.setPhase(attempt.examId, "extracting");
        long tLlm = System.nanoTime();
        AiProvider.ChatResponse res = provider.chat(new AiProvider.ChatRequest(
            ParseExamPrompts.SYSTEM_PROMPT,
            userContent,
            new AiProvider.Tool(
                ParseExamPrompts.TOOL_NAME,
                ParseExamPrompts.TOOL_DESCRIPTION,
                ParseExamPrompts.schema()
            )
        ));
        attempt.llmMs = msSince(tLlm);
        attempt.usage = res.usage();
        log.info("parse-exam-pdf[{}] timing step=llm-call took={}ms model={}",
            attempt.examId, attempt.llmMs, res.model());
        return res.toolArgs();
    }

    public static ParserStrategy.PdfMode effectivePdfMode(ParserStrategy strategy, boolean fastMode) {
        return fastMode ? ParserStrategy.PdfMode.TEXT_ONLY : strategy.pdfMode();
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

package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.ai.ConsentRequiredException;
import de.tum.cit.hestia.ai.ConsentScope;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.ai.internal.direct.DirectProviders;
import de.tum.cit.hestia.core.OrgContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The gateway: works out who a call is for, refuses an external model without consent, sends the
 * call to Logos or a direct provider and logs the answered call.
 */
@Service
class GatewayService implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final LogosChat logos;
    private final DirectProviders direct;
    private final ConsentService consents;
    private final JobService jobs;
    private final Callers callers;
    private final LlmCallRepository calls;
    private final TransactionTemplate ownTransaction;

    GatewayService(LogosChat logos, DirectProviders direct, ConsentService consents, JobService jobs, Callers callers,
            LlmCallRepository calls, PlatformTransactionManager transactions) {
        this.logos = logos;
        this.direct = direct;
        this.consents = consents;
        this.jobs = jobs;
        this.callers = callers;
        this.calls = calls;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        Optional<LlmJob> job = jobs.find(request.jobId());
        UUID userId = job.map(LlmJob::getUserId).orElseGet(() -> callers.currentUserId().orElse(null));
        UUID courseId = request.courseId() != null ? request.courseId() : job.map(LlmJob::getCourseId).orElse(null);
        ModelRef model = request.model();

        if (model.external() && !consents.isGranted(userId, ConsentScope.EXTERNAL_MODELS)) {
            throw new ConsentRequiredException(ConsentScope.EXTERNAL_MODELS);
        }
        LlmResponse response = model.external() ? direct.call(request) : logos.call(request);
        record(request, job.map(LlmJob::getId).orElse(null), userId, courseId, response);
        return response;
    }

    /** Logging must never fail a call that the model already answered (and that was paid for). */
    private void record(LlmRequest request, UUID jobId, UUID userId, UUID courseId, LlmResponse response) {
        if (OrgContext.current().isEmpty()) {
            log.warn("LLM call without organisation not logged: module={} purpose={} model={}",
                    request.module(), request.purpose(), response.model());
            return;
        }
        String model = response.model() != null ? response.model() : request.model().id();
        try {
            ownTransaction.executeWithoutResult(status -> calls.save(new LlmCall(jobId, userId, courseId,
                    request.module(), request.purpose(), request.model().provider().name(), model,
                    request.model().external(), response.usage().promptTokens(), response.usage().completionTokens())));
        } catch (RuntimeException e) {
            log.warn("Could not log LLM call (module={} purpose={}): {}", request.module(), request.purpose(), e.toString());
        }
    }
}

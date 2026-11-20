package de.tum.cit.hestia.assessment.ai;

import de.tum.cit.hestia.ai.ConsentApi;
import de.tum.cit.hestia.ai.ConsentScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in user's consent to external AI models (OpenAI, Anthropic, Google), for the client's
 * consent dialog and user menu. A thin layer over {@link ConsentApi}; the log of grants and
 * withdrawals lives in the {@code ai} module.
 */
@RestController
@RequestMapping("/api/ai-consent")
@Tag(name = "AI consent", description = "Consent to sending exam content to external AI providers.")
public class ConsentController {

    private final ConsentApi consents;

    public ConsentController(ConsentApi consents) {
        this.consents = consents;
    }

    public record ConsentResponse(boolean granted, String notice_version, String last_action,
                                  java.time.OffsetDateTime last_action_at, Notice notice) {}

    public record Notice(String version, String text) {}

    @Operation(summary = "Consent state and notice",
        description = "Whether the user currently consents, when they last decided, and the notice text (Markdown, German).")
    @GetMapping
    public ConsentResponse status() {
        return response(consents.status(ConsentScope.EXTERNAL_MODELS));
    }

    @Operation(summary = "Consent to the current notice", description = "Appends a grant to the consent log.")
    @PostMapping
    public ConsentResponse grant() {
        return response(consents.grant(ConsentScope.EXTERNAL_MODELS));
    }

    @Operation(summary = "Withdraw consent", description = "Appends a withdrawal to the consent log; effective for the future.")
    @DeleteMapping
    public ConsentResponse revoke() {
        return response(consents.revoke(ConsentScope.EXTERNAL_MODELS));
    }

    private ConsentResponse response(ConsentApi.Status status) {
        ConsentApi.Notice notice = consents.notice(ConsentScope.EXTERNAL_MODELS);
        return new ConsentResponse(status.granted(), status.noticeVersion(), status.lastAction(),
            status.lastActionAt(), new Notice(notice.version(), notice.text()));
    }
}

package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.ai.ConsentApi;
import de.tum.cit.hestia.ai.ConsentScope;
import de.tum.cit.hestia.core.OrgContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consents, kept as an append-only log. The notice text is versioned in the repository
 * ({@code resources/ai/consent-<scope>-<version>.md}); a changed text gets a new version, and a
 * consent counts only for the version it was given for.
 */
@Service
class ConsentService implements ConsentApi {

    /** Current notice version per scope. */
    static final Map<ConsentScope, String> NOTICE_VERSIONS = Map.of(ConsentScope.EXTERNAL_MODELS, "2026-11-20");

    /** An HTML comment with the line break and one blank line after it. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->[ \\t]*(\\R(?:[ \\t]*\\R)?)?", Pattern.DOTALL);

    private final ConsentEventRepository events;
    private final Callers callers;

    ConsentService(ConsentEventRepository events, Callers callers) {
        this.events = events;
        this.callers = callers;
    }

    @Override
    public Notice notice(ConsentScope scope) {
        String version = NOTICE_VERSIONS.get(scope);
        String name = "ai/consent-" + scope.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + version + ".md";
        try {
            String text = new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
            return new Notice(scope, version, withoutComments(text));
        } catch (IOException e) {
            throw new UncheckedIOException("Consent notice " + name + " missing", e);
        }
    }

    /**
     * The notice as users see it: HTML comments are notes for the people who review the text
     * (e.g. open points for the data protection office) and the markdown view would print them.
     */
    static String withoutComments(String markdown) {
        return COMMENT.matcher(markdown).replaceAll("");
    }

    @Override
    @Transactional(readOnly = true)
    public Status status(ConsentScope scope) {
        return statusOf(callers.requireUserId(), scope);
    }

    @Override
    @Transactional
    public Status grant(ConsentScope scope) {
        return append(scope, ConsentEvent.GRANTED);
    }

    @Override
    @Transactional
    public Status revoke(ConsentScope scope) {
        return append(scope, ConsentEvent.REVOKED);
    }

    /**
     * Whether {@code userId} currently consents to {@code scope}: the latest entry is a grant for the
     * current notice version. False without an organisation on the thread (nothing is visible then).
     */
    @Transactional(readOnly = true)
    public boolean isGranted(UUID userId, ConsentScope scope) {
        if (userId == null || OrgContext.current().isEmpty()) {
            return false;
        }
        return statusOf(userId, scope).granted();
    }

    private Status append(ConsentScope scope, String action) {
        UUID userId = callers.requireUserId();
        events.saveAndFlush(new ConsentEvent(userId, scope.name(), action, NOTICE_VERSIONS.get(scope)));
        return statusOf(userId, scope);
    }

    private Status statusOf(UUID userId, ConsentScope scope) {
        String current = NOTICE_VERSIONS.get(scope);
        Optional<ConsentEvent> latest = events.findFirstByUserIdAndScopeOrderByCreatedAtDesc(userId, scope.name());
        boolean granted = latest
                .map(e -> ConsentEvent.GRANTED.equals(e.getAction()) && current.equals(e.getNoticeVersion()))
                .orElse(false);
        return new Status(scope, granted, current,
                latest.map(ConsentEvent::getAction).orElse(null),
                latest.map(ConsentEvent::getCreatedAt).orElse(null));
    }
}

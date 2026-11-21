package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.internal.security.FederationMetadata.IdentityProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code GET /saml2/discovery}: where a user picks their home university before the SAML login.
 * The TUM login is the first, prominent choice; below it every identity provider of the federation,
 * searchable by name ({@code ?q=}, answered by the server, so the page needs no script). Without a
 * federation the page forwards straight to the TUM login, so the clients can always link here.
 *
 * <p>Like the other SAML paths it has no module prefix; each client's nginx proxies it with its
 * prefix in X-Forwarded-Prefix. The links are relative, so they keep that prefix.
 */
final class DiscoveryPage extends OncePerRequestFilter {

    static final String PATH = "/saml2/discovery";
    private static final RequestMatcher MATCHER = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, PATH);
    private static final int MAX_QUERY = 100;

    private final FederationMetadata federation;
    private final FederatedRegistrations registrations;
    private final String homeLabel;

    DiscoveryPage(FederationMetadata federation, FederatedRegistrations registrations, String homeLabel) {
        this.federation = federation;
        this.registrations = registrations;
        this.homeLabel = homeLabel;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MATCHER.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!federation.enabled()) {
            response.sendRedirect(ServletUriComponentsBuilder.fromContextPath(request)
                    .path("/saml2/authenticate/" + FederatedRegistrations.HOME).toUriString());
            return;
        }
        String query = Optional.ofNullable(request.getParameter("q")).map(String::trim)
                .map(q -> q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q).orElse("");
        Optional<String> home = registrations.homeEntityId();
        List<IdentityProvider> providers = federation.identityProviders().stream()
                .filter(p -> home.map(h -> !h.equals(p.entityId())).orElse(true))
                .filter(p -> p.matches(query))
                .toList();

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; "
                + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(html(query, providers).getBytes(StandardCharsets.UTF_8));
    }

    private String html(String query, List<IdentityProvider> providers) {
        StringBuilder list = new StringBuilder();
        for (IdentityProvider p : providers) {
            list.append("<li><a href=\"authenticate/")
                    .append(FederatedRegistrations.registrationId(p.entityId()))
                    .append("\">").append(e(p.displayName())).append("</a></li>\n");
        }
        String empty = providers.isEmpty()
                ? "<p class=\"hint\">Keine Hochschule gefunden. / No university found.</p>" : "";
        return """
                <!doctype html>
                <html lang="de">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>HESTIA – Anmelden</title>
                <style>
                  body { font-family: system-ui, sans-serif; max-width: 40rem; margin: 2rem auto; padding: 0 1rem; color: #1f2937; }
                  h1 { font-size: 1.5rem; }
                  .home { display: block; text-align: center; padding: .9rem; border-radius: .5rem; background: #3070b3;
                          color: #fff; font-weight: 600; text-decoration: none; margin: 1rem 0 2rem; }
                  form { display: flex; gap: .5rem; }
                  input { flex: 1; padding: .5rem; font-size: 1rem; }
                  button { padding: .5rem 1rem; font-size: 1rem; }
                  ul { list-style: none; padding: 0; }
                  li a { display: block; padding: .5rem; border-bottom: 1px solid #e5e7eb; color: inherit; text-decoration: none; }
                  li a:hover { background: #f3f4f6; }
                  .hint { color: #6b7280; }
                </style>
                </head>
                <body>
                <h1>Anmelden / Sign in</h1>
                <a class="home" href="authenticate/%s">%s</a>
                <h2>Andere Hochschule / Other university</h2>
                <form method="get" action="discovery">
                  <input type="search" name="q" value="%s" placeholder="Name der Hochschule / University name" aria-label="Hochschule suchen">
                  <button type="submit">Suchen</button>
                </form>
                %s<ul>
                %s</ul>
                </body>
                </html>
                """.formatted(FederatedRegistrations.HOME, e(homeLabel), e(query), empty, list);
    }

    private static String e(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }
}

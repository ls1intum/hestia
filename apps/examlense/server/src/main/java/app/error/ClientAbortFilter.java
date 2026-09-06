package app.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Swallows the exception a request throws when its client has already gone away.
 *
 * <p>A browser dropping an SSE stream — closed tab, navigation, a dev-server hot
 * reload — leaves the server writing into a dead socket. {@code SseHub} already
 * catches that and evicts the emitter, but by then Spring has flagged the
 * abandoned async request unusable, and the container error-dispatches it
 * afterwards. Tomcat's {@code StandardWrapperValve} then logs a full
 * "Servlet.service() ... threw exception" stack at ERROR for what is ordinary
 * teardown, which buries real failures.
 *
 * <p>This filter sits above {@code DispatcherServlet}, so it intercepts that
 * dispatch before the container sees it. It is deliberately narrow: only a
 * disconnect the client caused is swallowed, and anything else propagates
 * untouched. Nothing is lost by returning — there is no longer a client to
 * write a response to.
 */
public class ClientAbortFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientAbortFilter.class);

    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * The abandoned request is finished by an ASYNC (then ERROR) dispatch, not by
     * the original REQUEST one — and {@link OncePerRequestFilter} skips both by
     * default. Without these two overrides the filter never sees the throwable it
     * exists to catch.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!isClientDisconnect(e)) throw e;
            log.debug("client disconnected during {} {}: {}",
                request.getMethod(), request.getRequestURI(), rootMessage(e));
        }
    }

    /**
     * True when the throwable chain is a client-side disconnect.
     *
     * <p>Matched by type name rather than by importing the classes: {@code
     * ClientAbortException} is Tomcat-specific and would tie this to a servlet
     * container. Message matching is the fallback because a plain broken-pipe
     * arrives as a bare {@link IOException} with no distinguishing type.
     */
    static boolean isClientDisconnect(Throwable e) {
        // Bounded rather than followed to the end: a cause chain can be cyclic
        // (a -> b -> a), which no self-reference check would catch, and a request
        // thread spinning here would be a worse bug than the one being fixed.
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            String type = t.getClass().getSimpleName();
            if (type.equals("ClientAbortException")
                || type.equals("AsyncRequestNotUsableException")) {
                return true;
            }
            if (t instanceof IOException && isDisconnectMessage(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDisconnectMessage(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("broken pipe")
            || m.contains("connection reset")
            || m.contains("connection abort")
            || m.contains("an established connection was aborted"); // Windows wording
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
            t = t.getCause();
        }
        return t.toString();
    }
}

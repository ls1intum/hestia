package app.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's IP, shared by the request rate limiter and the registration cap so
 * the two cannot disagree about who a caller is.
 *
 * <p>Prefers {@code X-Client-Ip}, which this app's nginx sets with
 * {@code proxy_set_header ... $remote_addr} after its {@code real_ip} module has
 * resolved the true client. Because nginx <em>overwrites</em> that header, a value
 * a client sends under the same name is discarded, which is what makes it
 * trustworthy.
 *
 * <p>The application cannot derive this from {@code X-Forwarded-For} itself.
 * Spring's {@code ForwardedHeaderFilter} — enabled by
 * {@code server.forward-headers-strategy=framework}, which the app needs for https
 * URL generation — runs at {@code HIGHEST_PRECEDENCE}, hides the
 * {@code X-Forwarded-*} headers from all downstream code, and rewrites
 * {@code getRemoteAddr()} from the <em>leftmost</em> entry, the part the client
 * supplies. No filter can be ordered ahead of it, so the resolution has to happen
 * at the proxy.
 *
 * <p>The {@code X-Forwarded-For} branch below is the fallback for a deployment
 * whose proxy does not set {@code X-Client-Ip}. Each proxy appends the address it
 * received the request from, so the header reads
 * {@code [anything the client sent] , real-client-ip , traefik-ip}: the client's
 * address is the first entry our own infrastructure appended,
 * {@code hops[size - trustedProxyHops]}. A client can only prepend, which shifts
 * that index right by exactly as many entries as it added, so the result does not
 * move.
 *
 * <p>Both paths trust the edge. Anything that reaches the server without passing
 * through nginx can name itself whatever it likes — the same caveat that applies to
 * every header-based client identification.
 */
public final class ClientIp {

    private ClientIp() {}

    /**
     * @param behindProxy      whether a reverse proxy is actually in front; when false the
     *                         forwarded header is ignored entirely, since a direct caller
     *                         could otherwise set it freely
     * @param trustedProxyHops how many entries our own proxies append to the header
     */
    public static String of(HttpServletRequest req, boolean behindProxy, int trustedProxyHops) {
        if (!behindProxy) return req.getRemoteAddr();

        String fromProxy = req.getHeader("X-Client-Ip");
        if (fromProxy != null && !fromProxy.isBlank()) return fromProxy.trim();

        return fromForwardedFor(req, trustedProxyHops);
    }

    private static String fromForwardedFor(HttpServletRequest req, int trustedProxyHops) {
        String header = req.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) return req.getRemoteAddr();

        String[] raw = header.split(",");
        String[] hops = new String[raw.length];
        int count = 0;
        for (String hop : raw) {
            String trimmed = hop.trim();
            if (!trimmed.isEmpty()) hops[count++] = trimmed;
        }
        if (count == 0) return req.getRemoteAddr();

        // Clamped rather than rejected: a chain shorter than configured means the hop
        // count and the real topology disagree, and the leftmost entry is then the
        // best available answer.
        int index = Math.max(0, count - Math.max(1, trustedProxyHops));
        return hops[index];
    }
}

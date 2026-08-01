package ug.co.smsone.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one way a filter in this codebase asks "which path is this request for?".
 *
 * <p>Every cross-cutting filter decides whether it applies by matching a path prefix, and every one of
 * them must make that decision the same way or the chain disagrees with itself. The trap is
 * {@code getRequestURI()}: it carries the deployment's {@code server.servlet.context-path}, so under a
 * non-empty context path a {@code startsWith("/api/")} test that reads it silently stops matching while
 * a sibling filter reading {@code getServletPath()} keeps working — the filters then guard different
 * sets of requests, which is the kind of drift nobody notices until a deployment moves.
 *
 * <p>Existed as three byte-identical private copies before it was hoisted here (`ProvisioningGateFilter`,
 * `RateLimitFilter`, `ImpersonationFilter`) plus one divergent fourth (`IdempotencyFilter`).
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** Context-relative path (so a non-empty {@code server.servlet.context-path} can't blind a filter). */
    public static String of(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return (servletPath != null && !servletPath.isEmpty()) ? servletPath : request.getRequestURI();
    }
}

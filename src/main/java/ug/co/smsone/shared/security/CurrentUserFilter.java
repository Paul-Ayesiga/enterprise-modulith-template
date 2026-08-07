package ug.co.smsone.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller once, here, and clears the thread on the way out. It owns no policy — it decides
 * nothing and rejects nobody — it only fixes WHEN the single resolution in {@link CurrentUserProvider}
 * happens.
 *
 * <p><b>Why a filter and not lazy resolution.</b> Resolution reads the database, and one of its callers
 * is the JPA auditor, which runs inside a Hibernate flush. Issuing a query from inside a flush is how you
 * get an action-queue assertion instead of a saved row. Resolving here — on the request thread, before
 * any controller, service or repository can be reached — means the auditor always reads a memo that is
 * already there. Off a request thread (jobs, listeners, startup) there is no authentication at all, so
 * there is no query to mistime: a non-person actor writes NULL and that is the schema's own answer.
 *
 * <p>The memo is per THREAD, so anything that installs a {@code SecurityContext} on a thread of its own —
 * {@code McpToolDispatcher} does, because the MCP SDK owns scheduling — must resolve the caller there
 * before it opens a transaction, for the same reason. One {@code currentUser()} call immediately after
 * the context is installed is the whole obligation.
 *
 * <p>{@code @Order(-1)} places it after the impersonation swap ({@code -2}) so the EFFECTIVE identity is
 * what gets resolved, and before rate limiting, idempotency and the provisioning gate, which all key on
 * it. {@link OrgMdcFilter} shares this order and the tie is deliberately irrelevant: that filter only
 * READS through the same provider, so whichever of the two runs first performs the one resolution and the
 * other is served the memo.
 *
 * <p>The clear is not tidiness. Request threads are pooled, and the memo holds who the last caller was;
 * {@link CurrentUserProvider} already refuses to serve an entry that was not built from the current
 * {@code Authentication}, so leaving it would be inert rather than dangerous — but identity that outlives
 * its request is exactly the kind of inert that stops being inert after somebody edits one line.
 */
@Component
@Order(-1)
class CurrentUserFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserFilter.class);

    private final CurrentUserProvider currentUserProvider;

    CurrentUserFilter(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            warm();
            chain.doFilter(request, response);
        } finally {
            CurrentUserProvider.clear();
        }
    }

    private void warm() {
        try {
            currentUserProvider.currentUser();
        } catch (RuntimeException ex) {
            // A failure here must not become the response. Throwing from a filter bypasses
            // GlobalExceptionHandler and renders a non-envelope 500; letting the request continue means
            // the next caller retries the same resolution INSIDE the dispatcher, where the failure is
            // rendered as the envelope like every other error. Requests that never ask who is calling
            // (public probes, docs) are unaffected either way.
            log.warn("Deferred caller resolution after a failure at the edge: {}", ex.toString(), ex);
        }
    }
}

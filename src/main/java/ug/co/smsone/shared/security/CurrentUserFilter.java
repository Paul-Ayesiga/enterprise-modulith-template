package ug.co.smsone.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * Resolves the caller once, here, pins the TENANT that resolution names, and clears both on the way
 * out. It owns no authorization policy — it decides nothing about what a caller may reach — it fixes
 * WHEN the single resolution in {@link CurrentUserProvider} happens and WHICH schema the rest of the
 * request reads from.
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
 * the context is installed is the whole obligation, and since ADR 0010 the tenant travels with it.
 *
 * <p>{@code @Order(-1)} places it after the impersonation swap ({@code -2}) so the EFFECTIVE identity is
 * what gets resolved, and before rate limiting, idempotency and the provisioning gate, which all key on
 * it. {@link OrgMdcFilter} shares this order and the tie is deliberately irrelevant: that filter only
 * READS through the same provider, so whichever of the two runs first performs the one resolution and the
 * other is served the memo.
 *
 * <h2>The tenant pin (ADR 0010 §3.2)</h2>
 *
 * <p>The tenant is pinned HERE rather than in a filter of its own, and the order is the argument:
 * {@code OrgPolicyEnforcementFilter} ({@code @Order 3}) reads {@code org_security_policy} and
 * {@code user_device_trust}, and {@code SubscriptionAccessFilter} ({@code @Order 5}) reads
 * {@code org_subscription} — all tenant-tier tables — so the router has to sit before them. This filter
 * already resolves the caller, already clears in a {@code finally}, and already runs after the
 * impersonation swap, so an impersonated request pins the TARGET's org, which is what AGENTS §5.5 asks
 * for. Extending it means one filter, one lifecycle, one {@code finally}; a second filter would duplicate
 * that discipline and could drift out of order.
 *
 * <p><b>PLATFORM is installed before resolution, and that is not a fallback.</b> Resolution is itself a
 * database read — {@code external_identity}, {@code person}, {@code external_organization},
 * {@code organization} — and with the tenant still absent those reads borrow a connection pointed at the
 * empty {@code no_tenant} schema and fail. Pinning PLATFORM first states the truth of that window: no
 * tenant is known yet and everything read in it is platform-tier. It is not the "absent → fall back to
 * platform" the ADR forbids — that rule is about the routing DataSource's treatment of the absent state,
 * which stays {@code no_tenant} unconditionally. Note the window this filter cannot cover: the security
 * chain runs at {@code -100} and {@code ImpersonationFilter} at {@code -2}, so {@code api_key} and
 * {@code impersonation_session} are read before any pin exists here.
 *
 * <p><b>A caller with no tenant on a tenant-scoped route is refused, 403, before the chain.</b> That is
 * layer 1 of ADR 0010 §3.3 — the clean, intended failure, in the envelope with the request id — and its
 * value is that the ugly layers behind it (an unqualified read against an empty schema, 500) stay
 * unreachable. {@link #ORG_SCOPED_PATH} is what "tenant-scoped" means here, and its javadoc is the one to
 * read before adding an endpoint — the short version is that there is nothing to register. The refusal is
 * written straight to the response rather than thrown: throwing from a filter bypasses
 * {@code GlobalExceptionHandler} and renders a non-envelope 500, which is the failure this whole mechanism
 * exists to avoid.
 *
 * <p>The clear is not tidiness — but read {@code McpToolDispatcher} for where it actually bites.
 * {@code spring.threads.virtual.enabled} is on, so request threads are never reused and neither the memo
 * nor the tenant can leak from one HTTP request to the next; a test at this level passes with the
 * {@code finally} deleted. The leak lives on POOLED platform threads — the scheduler, {@code @Async}
 * executors, the MCP SDK's — which is where it is tested.
 */
@Component
@Order(-1)
class CurrentUserFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserFilter.class);

    /**
     * <b>The rule: a request is refused for having no tenant only when its PATH NAMES ONE.</b> That is
     * the whole of ADR 0010 §3.3 layer 1, and the predicate is the route, never the caller — "the caller
     * holds no organization" is a perfectly ordinary state (see below), so it cannot be the thing that
     * decides.
     *
     * <p>In this codebase a route names an organization by carrying it in the path, and the convention is
     * exhaustive: <b>every one of the fifteen tenant-scoped controllers is mapped under
     * {@code /api/v1/orgs/{orgId}/…}</b>. Nothing else reads a tenant-tier table on the caller's own
     * tenant. So the tenant-scoped surface is expressible as one pattern, and it is the same one
     * {@code OrgPolicyEnforcementFilter} ({@code @Order 3}), {@code MaintenanceFilter} ({@code @Order 4})
     * and {@code SubscriptionAccessFilter} ({@code @Order 5}) already use to recognise an org call — the
     * three filters this pin exists to keep from reading {@code org_security_policy},
     * {@code maintenance_window} and {@code org_subscription} without a tenant. One definition of "org
     * call", four filters.
     *
     * <p><b>Why not an allowlist of the routes that have no tenant.</b> That was the first shape of this
     * gate and it was wrong in kind, not in contents. It made "refuse" the default for the entire URL
     * space, so it had to enumerate the complement of one prefix: every catalog, the whole personal
     * surface, the operator surface, the vendor callbacks, the docs, the actuator — and it still 403'd
     * {@code /no/such/resource}, turning every 404 in the application into a refusal, and every test
     * controller with it. A list whose complement is a single prefix should be written as that prefix.
     * The failure modes are also asymmetric in the wrong direction: a forgotten entry was a 403 on a
     * working endpoint — silent, shipped, and found by a customer — whereas a route that needs a tenant
     * and is not matched here still fails loudly and locally at ADR §3.3 layers 2–3, because absent
     * routes to the empty {@code no_tenant} schema and the read dies with {@code relation "…" does not
     * exist}. Guessing wrong now costs a 500 in a test run instead of a 403 in production.
     *
     * <p><b>Adding an endpoint: there is nothing to register here</b> — provided it follows the
     * convention. If it serves the caller's own organization, map it under {@code /api/v1/orgs/{orgId}/…}
     * like the other fifteen and it is covered the moment it exists. If you are ever tempted to serve a
     * tenant implicitly from the token with no {@code orgId} in the path, that is the case this pattern
     * cannot see: extend it in the same commit, and expect the three filters above to need the same edit,
     * since they read the tenant's tables on that request too.
     *
     * <p><b>What is deliberately NOT matched.</b>
     *
     * <ul>
     *   <li>{@code /api/v1/admin/orgs/{orgId}/…} ({@code retention}, {@code sla}) reads tenant-tier
     *       tables while naming an org in its path, and is still excluded — on purpose. It is a
     *       cross-tenant operator route: the caller has no organization and never will, and the tenant is
     *       entered explicitly and audibly inside the service by {@code TenantContext.runAs(orgId, …)},
     *       which ADR §2 requires for exactly these tables. Refusing it here would 403 the only caller it
     *       has. The anchor at {@code /api/v1/orgs/} rather than a bare {@code /orgs/} is what keeps that
     *       distinction, and it is the reason the pattern is anchored at all.</li>
     *   <li>{@code POST /api/v1/orgs} — creating a tenant, which by definition happens before one exists.
     *       It names no org because there is not yet an org to name.</li>
     *   <li>The unauthenticated. The security chain is {@code @Order(-100)}, so anything reaching this
     *       filter already passed {@code anyRequest().authenticated()}; anonymous callers only ever arrive
     *       on {@code SecurityConfig}'s {@code permitAll} matchers, none of which is under
     *       {@code /api/v1/orgs/{orgId}}.</li>
     *   <li>The personal surface, and this is the one the old default got wrong most expensively.
     *       {@code CurrentUserProvider} yields an org only for a token naming exactly ONE — a claim of
     *       zero, or of several, names no tenant — so a person seated in two organizations arrives with no
     *       organization BY DESIGN, and {@code /api/v1/me/organizations} is how they choose one. Refuse
     *       the org-less caller by default and a multi-org human can never reach any tenant at all.</li>
     * </ul>
     *
     * <p><b>The one caller-shaped exception, which no path pattern can express.</b>
     * {@code /api/v1/orgs/{orgId}/suspend} and {@code /reactivate} are {@code hasRole('platform-admin')}
     * precisely because governing an org cannot be gated on a permission held inside it — so the same
     * path is tenant-scoped for a member and platform-scoped for an operator. {@link #isPlatformOperator}
     * says that, in the codebase's own vocabulary: any tier satisfies {@link PlatformRole#SUPPORT}, the
     * floor of the hierarchy. It must be that and not "holds any role" — {@code roles} carries every
     * Keycloak realm role and {@code offline_access} is on essentially every human token. A non-operator
     * on a route they may not have keeps being refused by method security, which is what the tests that
     * assert it are about. Under impersonation {@code roles} is empty by construction, so an operator
     * inside a session is judged as the target — the target's tenant or the target's refusal, never the
     * operator's reach.
     */
    private static final Pattern ORG_SCOPED_PATH = Pattern.compile("^/api/v1/orgs/[^/]+(/.*)?$");

    private final CurrentUserProvider currentUserProvider;
    private final EnvelopeErrorWriter errorWriter;

    CurrentUserFilter(CurrentUserProvider currentUserProvider, EnvelopeErrorWriter errorWriter) {
        this.currentUserProvider = currentUserProvider;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // RESTORE, not clear. A request is not always the outermost holder of the axis: the MCP
        // dispatcher pins before invoking a tool, and every integration test pins PLATFORM on the test
        // thread before driving MockMvc. Clearing unconditionally would hand the thread back with LESS
        // than it arrived with, so the caller's next database read — the assertion at the bottom of the
        // test, the second tool call in a dispatch — would land on no_tenant and fail with a relation
        // that plainly exists. Restore is also what makes nesting safe in general, which is why the
        // dispatcher already does it.
        Tenant previous = TenantContext.current();
        try {
            TenantContext.setPlatform();
            if (pin(request, response)) {
                chain.doFilter(request, response);
            }
        } finally {
            TenantContext.restore(previous);
            CurrentUserProvider.clear();
        }
    }

    /**
     * Resolve the caller once and put the tenant that resolution names on the thread.
     *
     * @return {@code true} when the request may proceed; {@code false} when it was refused and the
     *         envelope has already been written to the response
     */
    private boolean pin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean tenantScoped = isOrgScopedRoute(RequestPaths.of(request));
        CurrentUser caller;
        try {
            caller = currentUserProvider.currentUser().orElse(null);
        } catch (RuntimeException ex) {
            // A failure here must not become the response verbatim. Throwing from a filter bypasses
            // GlobalExceptionHandler and renders a non-envelope 500.
            log.warn("Deferred caller resolution after a failure at the edge: {}", ex.toString(), ex);
            if (!tenantScoped) {
                // Unchanged from before the pin existed: a route that never asks who is calling is
                // unaffected, and any caller that does ask retries the same resolution INSIDE the
                // dispatcher, where the failure is rendered as the envelope like every other error.
                return true;
            }
            // A tenant-scoped route cannot continue on a tenant we failed to learn — but it must not
            // answer 403 either. 403 says "you may not"; this is "we could not ask", usually a database
            // the caller has nothing to do with, and a blip that told every tenant their access was
            // denied is a worse outage than the blip.
            errorWriter.write(request, response, ErrorCode.INTERNAL_ERROR,
                    "The request could not be processed.", null);
            return false;
        }
        UUID orgId = caller == null ? null : caller.organizationId();
        if (orgId != null) {
            TenantContext.set(orgId);
            return true;
        }
        if (!tenantScoped || isPlatformOperator(caller)) {
            return true; // stays PLATFORM: no tenant is named, and none is missing
        }
        errorWriter.write(request, response, ErrorCode.FORBIDDEN,
                "This endpoint is scoped to an organization and your credential names none. Use a token "
                        + "scoped to exactly one organization, or a key minted inside it.", null);
        return false;
    }

    /**
     * Does the path name an organization? {@link RequestPaths} rather than {@code getRequestURI()} for
     * the reason that class exists: a non-empty {@code context-path} would otherwise blind this filter
     * while its three siblings kept matching, and they would then guard a different set of requests than
     * the pin that has to precede them.
     *
     * <p>The org segment is {@code [^/]+} and not the {@code [0-9a-fA-F-]{36}} its siblings use, and the
     * difference is intentional: they PARSE the segment to compare it with the caller's own org and must
     * step aside when it is not a UUID, whereas this filter only asks whether the route is one that
     * serves an organization. Judging that on whether the id happens to be well-formed would mean a
     * mistyped id demotes a tenant-scoped route to a tenant-less one, which is a narrower gate decided by
     * the client. The clean refusal is the right answer for {@code /api/v1/orgs/nonsense/tickets} too.
     */
    private static boolean isOrgScopedRoute(String path) {
        return ORG_SCOPED_PATH.matcher(path).matches();
    }

    /**
     * Any platform tier, asked at the floor of the hierarchy so a higher one satisfies it — the same
     * shape every {@code hasRole} check in this codebase uses, and {@code roles} arrives already
     * expanded through the {@code RoleHierarchy}.
     */
    private static boolean isPlatformOperator(CurrentUser caller) {
        return caller != null && caller.hasRole(PlatformRole.SUPPORT);
    }
}

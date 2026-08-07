package ug.co.smsone.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.identity.internal.PersonAccessService.Decision;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.security.ApiKeyAuthenticationToken;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * No-JIT access gate: an authenticated request to {@code /api/**} requires a provisioned {@code person},
 * else 403 — so a valid Keycloak JWT is not, by itself, access. Runs after the security chain (the
 * subject is available) and lazily activates INVITED → ACTIVE on the first real API hit.
 *
 * <p>{@code GET /api/v1/me} is the one lenient path — unprovisioned humans may render onboarding — but
 * it is method-scoped and never lenient for DISABLED accounts: disabled means no access at all.
 *
 * <p>Under an impersonation session this gate evaluates the TARGET, because {@code ImpersonationFilter}
 * swapped the principal at {@code @Order(-2)}. It therefore refuses exactly the accounts it refuses to
 * their owners — and does <em>not</em> activate them: see {@link #decide}.
 */
@Component
@Order(2) // after idempotency (1): a replayed response short-circuits before the gate re-runs
@ConditionalOnProperty(name = "app.provisioning.gate-enabled", havingValue = "true", matchIfMissing = true)
class ProvisioningGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningGateFilter.class);
    private static final String ME_PATH = "/api/v1/me";

    private final PersonAccessService access;
    private final CurrentUserProvider currentUserProvider;
    private final EnvelopeErrorWriter errorWriter;

    ProvisioningGateFilter(PersonAccessService access, CurrentUserProvider currentUserProvider,
            EnvelopeErrorWriter errorWriter) {
        this.access = access;
        this.currentUserProvider = currentUserProvider;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isMachine()) {
            chain.doFilter(request, response);
            return;
        }
        CurrentUser user = currentUserProvider.currentUser().orElse(null);
        if (user == null) {
            chain.doFilter(request, response); // unauthenticated — let the security chain handle it
            return;
        }
        Decision decision;
        try {
            decision = decide(request, user);
        } catch (RuntimeException ex) {
            // A filter exception would bypass GlobalExceptionHandler and produce a non-envelope 500;
            // render the envelope ourselves (never leak the exception to the client).
            log.error("Provisioning gate failed for person {}: {}", user.personId(), ex.toString(), ex);
            errorWriter.write(request, response, ErrorCode.INTERNAL_ERROR,
                    "The request could not be processed.", null);
            return;
        }
        switch (decision) {
            case ALLOWED -> chain.doFilter(request, response);
            case NOT_PROVISIONED -> errorWriter.write(request, response, ErrorCode.ACCOUNT_NOT_PROVISIONED,
                    "Your account has not been provisioned. Contact an administrator.", null);
            case DISABLED -> errorWriter.write(request, response, ErrorCode.ACCOUNT_DISABLED,
                    "Your account has been disabled.", null);
        }
    }

    /**
     * A machine credential is not a person, and this gate has nothing to say about it.
     *
     * <p>This was a live 403: an {@code X-Api-Key} request used to carry the attribution string
     * {@code key:<id>} as its subject — a value no identity provider ever issued and therefore one that
     * resolves to no person. The gate then answered ACCOUNT_NOT_PROVISIONED, telling the owner of a
     * valid, org-scoped, permission-capped key to "contact an administrator". It stayed invisible
     * because the gate is on in {@code application.yaml} and off in {@code application-test.yaml}, so
     * the whole suite ran with the one switch that hides it. A machine's person id is now null by
     * construction, which would land in the very same branch — hence this short-circuit stays.
     *
     * <p>The fix is not to give machines a person row — the schema refuses that on purpose
     * ({@link IdentityProvider#API_KEY} is reserved and unused, and a synthetic person per key would be
     * eligible for memberships, audit attribution and erasure requests). It is to notice that no-JIT
     * asks a question about HUMANS: has an administrator admitted this person yet. A key was minted by
     * an administrator; that IS its admission, it is scoped and revocable at the key row, and
     * {@code ApiPermissionEvaluator} — not this filter — bounds what it can reach.
     *
     * <p>Read from the {@code Authentication} rather than by sniffing the subject string for a prefix:
     * the credential's own type is the fact, {@code "key:"} is a rendering of it, and the house already
     * tests the type this way ({@code PermissionEscalationGuard}, {@code FoundationToolManifest}).
     */
    private static boolean isMachine() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof ApiKeyAuthenticationToken;
    }

    /**
     * The one place {@code authorize} (which writes) is chosen over {@code peek} (which does not).
     *
     * <p>Lazy INVITED → ACTIVE means "the person finally showed up", and an operator wearing them is
     * not them showing up: activating here would flip {@code status}, stamp {@code activated_at} and
     * publish {@code PersonActivated} on the strength of somebody else's read — a durable write from a
     * session that may well be READ_ONLY, stamped {@code updated_by = <the target>} because the
     * effective subject is theirs, and with no {@code audit_log} row naming the operator anywhere.
     * The "never signed in" signal any invite-expiry logic keys on would be destroyed silently.
     * {@code peek} returns the identical DISABLED / erased / NOT_PROVISIONED answers without it.
     */
    private Decision decide(HttpServletRequest request, CurrentUser user) {
        Decision decision = decideForPerson(user);
        if (isOnboardingRead(request)) {
            // No lazy activation from /me (peek, above), and NOT_PROVISIONED may pass — that IS
            // onboarding. DISABLED is still a hard stop.
            return decision == Decision.NOT_PROVISIONED ? Decision.ALLOWED : decision;
        }
        return decision;
    }

    /**
     * A caller with no person id is either not provisioned yet or erased, and only this module can tell
     * those apart — so the absence is explained from the raw token subject here, the sanctioned seam,
     * rather than from a shared port that would put a provisioning policy at the edge.
     *
     * <p>A token with no subject at all cannot be matched against anything, so it is NOT_PROVISIONED:
     * the lenient answer, because refusing it as DISABLED would tell a caller their account was deleted
     * on the strength of a malformed credential.
     */
    private Decision decideForPerson(CurrentUser user) {
        UUID personId = user.personId();
        if (personId == null) {
            return CallerSubject.of().map(access::explainAbsence).orElse(Decision.NOT_PROVISIONED);
        }
        // peek() under a session: activating is "the person finally showed up", and an operator wearing
        // them is not them showing up.
        return user.isImpersonated() ? access.peek(personId) : access.authorize(personId);
    }

    private boolean isOnboardingRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && ME_PATH.equals(RequestPaths.of(request));
    }
}

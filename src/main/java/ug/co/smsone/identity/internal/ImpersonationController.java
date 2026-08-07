package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The operator surface for impersonation: open a session, review your own, end one. {@code
 * platform-support} is the floor for all three — opening a WRITE session additionally needs {@code
 * platform-admin}, which is checked in the service because the mode is in the body and an annotation
 * cannot see it.
 *
 * <p>Unreachable from inside a session by construction: the impersonated principal carries no platform
 * role, so none of these handlers can be used to open another one.
 *
 * <p>{@code app.impersonation.enabled=false} makes every route here a 403, not a 404. The routes stay
 * mapped deliberately: a 404 is indistinguishable from a typo'd path or a version skew, so an operator
 * hitting a deployment with the switch off would debug their client instead of reading the one answer
 * that explains it. The same switch refuses {@code X-Impersonate} in {@code ImpersonationFilter}, so
 * both halves of the feature say the same thing.
 */
@RestController
@RequestMapping("/api/v1/admin/impersonations")
class ImpersonationController {

    private static final String RESOURCE_TYPE = "impersonation-session";

    private final ImpersonationService service;
    private final Clock clock;
    private final boolean enabled;

    ImpersonationController(ImpersonationService service, Clock clock,
            @Value("${app.impersonation.enabled:true}") boolean enabled) {
        this.service = service;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Every handler calls this first. It is repeated per method rather than hidden in a filter or an
     * annotation because the switch has to answer in the operator's words — a bare 403 from the generic
     * access-denied handler would read as "your tier is too low", which is the one thing it is not.
     */
    private void requireEnabled() {
        if (!enabled) {
            throw new ForbiddenException("Impersonation is disabled on this deployment.");
        }
    }

    /**
     * {@code active} is computed on read, which is the only place expiry is ever decided.
     *
     * <p>{@code targetDisplay} is the label frozen when the session opened, not a live lookup: this
     * listing is read most often about accounts that are gone, and a person id that resolves to nothing
     * is exactly what the column exists to spare an auditor.
     */
    record ImpersonationAttributes(String actorPersonId, String targetPersonId, String targetDisplay,
            String orgId, String reason, String mode, Instant startedAt, Instant expiresAt, Instant endedAt,
            String endedByPersonId, boolean active) {
    }

    /**
     * {@code mode} defaults to {@code READ_ONLY} and {@code ttl} (ISO-8601, e.g. {@code PT10M}) to the
     * configured default; both are server-bounded, so what the client sends is a request, not a grant.
     *
     * <p>{@code targetPersonId} names a person, not a Keycloak subject: the operator picked them out of
     * {@code GET /api/v1/admin/users}, which is a list of people, and a provider's id has no meaning to
     * anything on this side of the edge.
     */
    record OpenImpersonationRequest(@NotNull UUID targetPersonId, UUID orgId, @NotBlank String reason,
            String mode, String ttl) {
    }

    @PostMapping
    @Operation(summary = "Open an impersonation session",
            description = """
                    Defaults to a READ_ONLY session, which admits only GET, HEAD and OPTIONS; \
                    `mode=WRITE` is refused unless the caller holds platform-admin. `ttl` is an \
                    ISO-8601 duration bounded server-side — a request over the cap is rejected, never \
                    silently clamped — and `reason` must be at least 8 characters. Pass the returned id \
                    as the `X-Impersonate` header on the requests that should run as the target.""")
    @PreAuthorize("hasRole('platform-support')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject open(@Valid @RequestBody OpenImpersonationRequest request) {
        requireEnabled();
        ImpersonationSession session = service.open(request.targetPersonId(), request.orgId(), request.reason(),
                parseMode(request.mode()), parseTtl(request.ttl()));
        return toResource(session, clock.instant());
    }

    /**
     * The caller's own sessions by default. {@code ?actor=<personId>} names another operator's and needs
     * {@code platform-admin}, checked in the service — it is the only way a platform admin can find the
     * session id the DELETE below already lets them end.
     */
    @GetMapping
    @Operation(summary = "List an operator's impersonation sessions",
            description = """
                    The caller's own sessions, live and historical, newest first. `actor=<personId>` \
                    names another operator's instead and requires platform-admin. `active` is computed \
                    when the row is read, so a lapsed session reports inactive with no sweep job \
                    involved.""")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(@RequestParam(required = false) UUID actor, CursorPageRequest page) {
        requireEnabled();
        Instant now = clock.instant();
        return WindowedResult.of(service.list(actor, page), page, session -> toResource(session, now));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "End an impersonation session",
            description = """
                    Takes effect on the very next request: the session is re-authorized every time, so \
                    ending it revokes the reach it already granted rather than only the next open. Open \
                    to the operator who holds it and to any platform admin.""")
    @PreAuthorize("hasRole('platform-support')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(@PathVariable UUID id) {
        requireEnabled();
        service.end(id);
    }

    private static ImpersonationMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ImpersonationMode.READ_ONLY; // absent means the safe mode, never the capable one
        }
        try {
            return ImpersonationMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("mode must be READ_ONLY or WRITE.",
                    ApiSource.pointer("/data/attributes/mode"));
        }
    }

    private static Duration parseTtl(String ttl) {
        if (ttl == null || ttl.isBlank()) {
            return null; // absent: the service applies the configured default
        }
        try {
            return Duration.parse(ttl.trim());
        } catch (DateTimeParseException ex) {
            throw new ValidationException("ttl must be an ISO-8601 duration (e.g. PT15M).",
                    ApiSource.pointer("/data/attributes/ttl"));
        }
    }

    private static ResourceObject toResource(ImpersonationSession session, Instant now) {
        return new ResourceObject(session.getId().toString(), RESOURCE_TYPE,
                new ImpersonationAttributes(
                        session.getActorPersonId().toString(),
                        session.getTargetPersonId().toString(),
                        session.getTargetDisplay(),
                        session.getOrgId() == null ? null : session.getOrgId().toString(),
                        session.getReason(),
                        session.getMode().name(),
                        session.getStartedAt(),
                        session.getExpiresAt(),
                        session.getEndedAt(),
                        session.getEndedByPersonId() == null ? null : session.getEndedByPersonId().toString(),
                        session.isActive(now)));
    }
}

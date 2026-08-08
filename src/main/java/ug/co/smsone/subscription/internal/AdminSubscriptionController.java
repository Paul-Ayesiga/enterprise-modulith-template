package ug.co.smsone.subscription.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The platform's commercial controls: read a tenant's subscription, assign its plan, run a trial, and
 * manage the plan catalog itself (create / update / delete). Reads are {@code platform-support};
 * anything that changes what a tenant may do is {@code platform-admin} and audited. Class-level
 * {@code /api/v1/admin} mapping keeps the surface out of the X-Impersonate docs.
 *
 * <h2>Two surfaces, two tiers, and one route that is genuinely both</h2>
 *
 * <p>{@code /orgs/{orgId}/subscription…} touches {@code org_subscription}, which is TENANT-tier
 * (ADR 0010 §2), while {@code /plans…} touches {@code plan} and {@code plan_entitlement}, which are
 * platform-tier catalog. {@code /api/v1/admin/orgs/{orgId}/…} is deliberately not matched by
 * {@code CurrentUserFilter}'s org-scoped pattern — the caller is an operator with no organization —
 * so the per-org routes enter the tenant themselves, the cross-tenant admin write of ADR 0010 §5.15.
 * The catalog routes need no pin: their tables name their schema, so they resolve on the platform
 * axis the request already carries.
 *
 * <p>The exception is {@link #deletePlan}, which is a catalog write whose GUARD is a question about
 * every tenant — see the note there.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminSubscriptionController {

    private static final String PLAN_TYPE = "plan"; // wire contract — renaming breaks clients

    /**
     * The axis the delete guard borrows, when the question spans every tenant and names none.
     *
     * <p>It names no organization deliberately: an org that has never been promoted resolves to the
     * shared {@code tenant_pool}, and a UUID in no {@code organization} row can never resolve to
     * anything else — so this IS the pooled schema's axis, spelled with the only vocabulary
     * {@code TenantContext} has. Same constant, same reasoning as {@code MappedSchemaValidator} and
     * {@code WebhookSecretEncryptionMigrator}: one idiom for "the pool", not several.
     *
     * <p>When silos exist (ADR 0010 Phase 5) "is this plan assigned to anybody" stops being one query.
     * The loop belongs at that point, over {@code platform.tenant_placement}, refusing on the first
     * home that still holds an assignment.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final SubscriptionService subscriptions;

    AdminSubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    record AssignRequest(String plan) {
    }

    record TrialRequest(String plan, Integer days) {
    }

    record PlanAttributes(String code, String name, int rank, Map<String, Long> entitlements) {
    }

    record CreatePlanRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 100) String name,
            @PositiveOrZero int rank, Map<String, Long> entitlements) {
    }

    record UpdatePlanRequest(@NotBlank @Size(max = 100) String name, @PositiveOrZero int rank,
            Map<String, Long> entitlements) {
    }

    @GetMapping("/orgs/{orgId}/subscription")
    @Operation(summary = "Inspect a tenant's subscription as the platform")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject get(@PathVariable UUID orgId) {
        return SubscriptionResources.toResource(
                TenantContext.callAs(orgId, () -> subscriptions.view(orgId)));
    }

    @PutMapping("/orgs/{orgId}/subscription")
    @Operation(summary = "Assign a tenant's plan",
            description = """
                    Upserts the subscription onto the named plan (`FREE`, `PRO`, `ENTERPRISE` as \
                    seeded) in good standing. Takes effect on the very next gate check — the \
                    entitlement cache is evicted through `SubscriptionChanged`, which also fans \
                    out `org.subscription_changed` to the tenant's webhooks. Audited.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject assign(@PathVariable UUID orgId, @RequestBody AssignRequest request) {
        return SubscriptionResources.toResource(
                TenantContext.callAs(orgId, () -> subscriptions.assign(orgId, request.plan())));
    }

    @PostMapping("/orgs/{orgId}/subscription/trial")
    @Operation(summary = "Start a paid-plan trial",
            description = """
                    Runs the org on the named PAID plan (`PRO`/`ENTERPRISE`) as a `TRIALING` trial \
                    for `days` (default 14) — full access throughout. When the trial lapses unpaid \
                    the expiry job flips it to `PAUSED` and the org goes READ-ONLY (org-scoped \
                    writes answer 402) until a plan is assigned or a payment lands. `FREE` cannot \
                    be trialed. Audited; fans out `org.subscription_changed`.""")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject startTrial(@PathVariable UUID orgId, @RequestBody TrialRequest request) {
        int days = request.days() == null ? 0 : request.days();
        return SubscriptionResources.toResource(
                TenantContext.callAs(orgId, () -> subscriptions.beginTrial(orgId, request.plan(), days)));
    }

    @GetMapping("/plans")
    @Operation(summary = "List the plan catalog",
            description = "Rank-ordered. `entitlements` encoding: `-1` = feature on, a positive number = a "
                    + "cap, an absent key = off / unlimited.")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> plans() {
        return subscriptions.catalog().stream().map(AdminSubscriptionController::toPlanResource).toList();
    }

    @PostMapping("/plans")
    @Operation(summary = "Create a plan",
            description = "Adds a plan to the catalog. `entitlements`: send `null` (or `-1`) for feature-on, a "
                    + "positive number for a cap, omit the key for off / unlimited; unknown keys are refused. "
                    + "platform-admin; audited.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return toPlanResource(
                subscriptions.createPlan(request.code(), request.name(), request.rank(), request.entitlements()));
    }

    @PutMapping("/plans/{code}")
    @Operation(summary = "Update a plan",
            description = "Replaces the name, rank, and entitlements of an existing plan (its `code` is the "
                    + "identity and cannot change). Editing entitlements re-gates every org on the plan on "
                    + "the next check. platform-admin; audited.")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject updatePlan(@PathVariable String code, @Valid @RequestBody UpdatePlanRequest request) {
        return toPlanResource(
                subscriptions.updatePlan(code, request.name(), request.rank(), request.entitlements()));
    }

    @DeleteMapping("/plans/{code}")
    @Operation(summary = "Delete a plan",
            description = "Removes a plan from the catalog. Refused (409) for the FREE fallback or a plan "
                    + "assigned to any organization. platform-admin; audited.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlan(@PathVariable String code) {
        // The one catalog route that reads a TENANT table: before deleting, the service asks whether
        // any organization is still on this plan, and `org_subscription` is tenant-tier. So the whole
        // call runs on the pooled tenant's axis — the platform-tier `plan` rows it writes are reached
        // by name from there, exactly as they are from any tenant request, which is why this is one
        // pin and not two spans. It has to be here rather than inside the service: deletePlan is
        // @Transactional, and a pin set after the connection is bound is the silent no-op
        // TenantContext.set throws to prevent (ADR 0010 §3.2).
        TenantContext.runAs(POOLED_TENANT, () -> subscriptions.deletePlan(code));
    }

    /**
     * Map a plan to the wire. Feature-on reads as its stored {@code -1} sentinel: a translated {@code null}
     * would be dropped by the {@code non_null} serializer, hiding an enabled feature. A positive number is a
     * cap; an absent key is off / unlimited.
     */
    private static ResourceObject toPlanResource(Plan plan) {
        return new ResourceObject(plan.getCode(), PLAN_TYPE,
                new PlanAttributes(plan.getCode(), plan.getName(), plan.getRank(), plan.getEntitlements()));
    }
}

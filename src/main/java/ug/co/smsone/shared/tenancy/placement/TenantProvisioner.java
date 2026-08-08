package ug.co.smsone.shared.tenancy.placement;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Makes a tenant's home real, and — this is the part worth reading — makes it real <strong>before the
 * tenant is announced</strong> (ADR 0010 §4.3).
 *
 * <h2>The hazard this class is shaped by</h2>
 *
 * <p>{@code OrganizationRegistered} carries three {@code @ApplicationModuleListener}s:
 * {@code OrganizationTrialListener} writes {@code org_subscription}, {@code OrganizationBillingListener}
 * writes {@code billing_account}, and {@code SearchEventListeners} writes {@code search_document}. All
 * three are AFTER-COMMIT and asynchronous, and all three write TENANT-tier tables.
 *
 * <p>So if creating a tenant's schema were itself an after-commit listener, those three would race it.
 * Under a silo policy every new tenant would silently get no trial and no billing account — and because
 * the failures are outbox-retried, they would look like transient noise rather than like a signup path
 * that does not work. §4.3 names this explicitly, and it is why the ordering here is a structural
 * property rather than a comment asking somebody to be careful.
 *
 * <p><strong>The rule, in one line: a tenant is never ANNOUNCED before its schema can serve it.</strong>
 *
 * <p>Under the default {@link PlacementPolicy#POOLED} that rule costs nothing — {@code tenant_pool}
 * already exists, so there is nothing to build and nothing to order, and the registration transaction
 * is exactly what it always was. §4.3 calls this the strongest practical argument for the hybrid.
 * Under {@link PlacementPolicy#SILO_PER_ORG} the rule has teeth: the schema is named after the tenant,
 * so the tenant's row must exist first, which is what {@link PlacementState#PROVISIONING} is for. The
 * transition out of it is explicit and happens in the announcing transaction
 * ({@link TenantPlacements#announce}) — never in a listener, which is the shape that reintroduces the
 * race.
 *
 * <h2>The same path serves promotion, deliberately</h2>
 *
 * <p>A tenant that outgrows the pool later (ADR 0010 §6 hop 0→1, Phase 5) is created a schema and
 * migrated by {@link TenantSchemaMigrator#migrate} — the identical call, with the identical validation
 * and the identical registry write. That is what makes extraction <em>rehearsable</em> instead of a
 * one-off: promotion day executes code that every silo signup has already executed, rather than a
 * runbook whose first real run is the one that matters. The steps promotion adds — a freeze window,
 * the row copy, the verification, the placement flip, the cache eviction — are additions on top of this
 * path, not a second version of it.
 *
 * <h2>Outside a transaction, always</h2>
 *
 * <p>Two reasons, and both are the kind that fail silently. DDL plus Flyway's own transaction
 * management cannot be nested inside an application transaction; and {@link TenantPlacements#markFailed}
 * exists precisely to outlive a failure, so writing it inside the transaction that failed would roll it
 * back and leave the FAILED row this class promises unwritten. {@link #provisionHomeFor} therefore
 * refuses to run inside one, the same way {@code TenantContext.set} refuses — a loud failure in place
 * of a quiet one.
 */
@Component
public class TenantProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioner.class);

    private final TenantPlacements placements;
    private final TenantSchemaMigrator migrator;
    private final PlacementPolicy policy;

    TenantProvisioner(TenantPlacements placements, TenantSchemaMigrator migrator,
            TenantPlacementProperties properties) {
        this.placements = placements;
        this.migrator = migrator;
        this.policy = properties.policy();
    }

    /**
     * Whether a tenant's schema has to be built before the tenant may be announced — the question ADR
     * 0010 §4.3 turns on, and the only thing the organization-creation path asks about the policy.
     *
     * <p>It is phrased as the RULE rather than as the flag on purpose: a call site reading
     * {@code if (policy == SILO_PER_ORG)} would have to re-derive why that matters, and would silently
     * stop being right the day a third policy is added.
     */
    public boolean schemaPrecedesAnnouncement() {
        return policy.buildsASchema();
    }

    /**
     * The schema a tenant with this id belongs in. Answered from the policy with no database read, so
     * it can be asked in the same breath as the write that records it.
     */
    public String homeFor(UUID orgId) {
        return policy.homeFor(orgId);
    }

    /**
     * Builds this tenant's home and records it, leaving the tenant {@link PlacementState#PROVISIONING}
     * — placed, migrated, and <strong>not yet announced</strong>. The announcement is the caller's, in
     * the transaction that writes the tenant.
     *
     * <p>Only called when {@link #schemaPrecedesAnnouncement()} is true. Under the pooled policy there
     * is nothing here to do: the home exists, and its placement row is written inside the caller's own
     * transaction instead, which is what keeps a pooled signup a single atomic write.
     *
     * <p><strong>An already-ACTIVE tenant is returned untouched.</strong> Moving a tenant that is
     * already serving is PROMOTION — a freeze window, a copy, verified row counts, a cache eviction
     * (§6, Phase 5) — and it is not something a re-adopt or a retry may trigger as a side effect of a
     * config flag having been flipped since that tenant was created.
     *
     * @throws TenantProvisioningException if the schema could not be built; a queryable
     *     {@link PlacementState#FAILED} row naming the cause is left behind first
     */
    public TenantPlacement provisionHomeFor(UUID orgId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "provisionHomeFor must run OUTSIDE a transaction: it issues DDL and runs Flyway,"
                            + " neither of which nests, and a FAILED placement written inside the"
                            + " transaction that failed would roll back with it — leaving exactly the"
                            + " half-made tenant the registry exists to make impossible (ADR 0010 §4.3).");
        }
        Optional<TenantPlacement> existing = placements.find(orgId);
        if (existing.filter(TenantPlacement::isActive).isPresent()) {
            return existing.get();
        }

        String home = homeFor(orgId);
        placements.reserve(orgId, home);
        try {
            String version = migrator.migrate(home);
            // Keyed by schema, so the one statement that stamps a silo is the same one that stamps
            // five thousand pooled tenants when the pool moves. State is untouched: a schema being at
            // head says nothing about whether the tenants in it have been announced.
            placements.recordMigrated(home, version);
            log.info("tenant {} placed in schema {} at version {}", orgId, home, version);
        } catch (RuntimeException failure) {
            // Record, THEN rethrow. The registry row is the deliverable here — the exception reaches
            // one caller and is gone, while "which tenants are not fit to serve, and why" has to be
            // answerable tomorrow by somebody who was not on this call stack (§4.2).
            placements.markFailed(orgId, describe(failure));
            log.error("tenant {} could not be placed in schema {} — placement recorded FAILED",
                    orgId, home, failure);
            throw new TenantProvisioningException(failure);
        }
        return placements.find(orgId).orElseThrow(() -> new IllegalStateException(
                "placement for " + orgId + " vanished between being reserved and being read back"));
    }

    /**
     * Creates and migrates a tenant schema by NAME, recording the version against every tenant that
     * lives there. The fleet-facing half: Phase 5's promotion calls it for the silo it is about to copy
     * into, and Phase 4's runner calls it per schema across the fleet.
     *
     * @return the version the schema now sits at
     */
    public String materialize(String schemaName) {
        String version = migrator.migrate(schemaName);
        placements.recordMigrated(schemaName, version);
        return version;
    }

    /**
     * What goes in {@code last_error}. The message chain rather than a stack trace: Flyway's own
     * exception already names the script and the failing statement, and the trace is in the log line
     * beside it. What this column owes its reader is the sentence that says whether one tenant is
     * broken or the whole fleet is.
     */
    private static String describe(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable cause = failure; cause != null && message.length() < 2000; cause = cause.getCause()) {
            if (message.length() > 0) {
                message.append(" | caused by ");
            }
            message.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
        }
        return message.toString();
    }
}

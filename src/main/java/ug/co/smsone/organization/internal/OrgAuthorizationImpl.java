package ug.co.smsone.organization.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.OrgAuthorization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Implements the shared {@link OrgAuthorization} port. {@code permissions} is what the edge calls once
 * per request to fill {@code CurrentUser.permissions()}; {@code hasPermission} serves the callers with
 * no request context — a scheduled job authorizing the person who registered the schedule.
 *
 * <h2>This port declares its own tenant axis (ADR 0010 §3.2)</h2>
 *
 * <p>Both methods take the organization as an ARGUMENT, which is the whole reason the pin belongs
 * here. A caller that names an org in a parameter has not necessarily entered it: the edge calls this
 * while still on the PLATFORM axis, because resolving WHICH tenant the token names is itself a
 * platform-tier read and the pin for that tenant cannot precede its own answer. A scheduler naming an
 * org it read out of a row has no axis at all. Neither can be asked to know that answering "what may
 * this person do here" reads {@code membership}, {@code org_role} and {@code role_permission} — three
 * TENANT-tier tables (docs/DATA_MODEL.md). The module that owns those tables is the one that can say
 * so, so it does, once, here.
 *
 * <p><b>One span and not two, even though {@link PermissionResolver} reads both tiers.</b> Its other
 * read is {@code platform.organization}, and that table is reached BY NAME — {@code Organization}
 * declares {@code @Table(schema = "platform")}, so Hibernate emits the schema and the
 * {@code search_path} never enters into it. A qualified name resolves from every axis; a bare one
 * resolves only from the tenant's. So the tenant axis satisfies both halves on one connection, and
 * splitting this into a platform span plus a tenant span would buy nothing and cost the org-status
 * check its place inside the cached value.
 *
 * <p><b>Idempotent with an outer pin, and deliberately so.</b> {@code CurrentUserProvider} takes the
 * same axis around the same call; a nested {@code callAs} for the same org restores the caller's axis
 * on the way out and is otherwise a thread-local write. What it must NOT be asked to do is change axis
 * inside an active transaction — the connection is already bound and the schema already chosen — so a
 * caller that opens a transaction on one axis and then asks about a different tenant fails loudly here
 * with {@link TenantContext}'s own message. That is the correct answer: the fix is at that caller,
 * which has to pin before it opens the transaction.
 */
@Component
class OrgAuthorizationImpl implements OrgAuthorization {

    private final PermissionResolver resolver;

    OrgAuthorizationImpl(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean hasPermission(UUID personId, UUID organizationId, String permissionCode) {
        return permissions(personId, organizationId).contains(permissionCode);
    }

    @Override
    public Set<String> permissions(UUID personId, UUID organizationId) {
        // The null pair is answered BEFORE the pin, not inside it: with no organization there is no
        // axis to enter, and Tenant.Org refuses a null id rather than inventing one. A machine key or
        // a caller scoped to no tenant holds nothing here, which is the same answer the resolver's own
        // guard gives — kept in both places because each is reachable without the other.
        if (personId == null || organizationId == null) {
            return Set.of();
        }
        // A caller already inside a transaction has already borrowed its connection, so its axis is
        // fixed and TenantContext refuses to change it — by design, because a pin set after the borrow
        // would silently do nothing. Pinning unconditionally here therefore THREW for every
        // transactional caller, and the damage was invisible: ExchangeScheduleFiringJob catches per
        // schedule and advances nextRunAt, so scheduled exports would have stopped firing forever while
        // logging one line a minute. The port pins for the callers who genuinely have no axis yet — the
        // edge resolves a token on PLATFORM, a scheduler starts with none — and defers to the caller's
        // own when one is already established.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return resolver.resolve(personId, organizationId);
        }
        return TenantContext.callAs(organizationId, () -> resolver.resolve(personId, organizationId));
    }
}

package ug.co.smsone.apikeys.internal;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.apikeys.ApiKeyReminting;
import ug.co.smsone.shared.audit.AuditLog;

/**
 * {@link ApiKeyReminting}, implemented — ADR 0010 §6 item 8's platform half.
 *
 * <p><b>Revoke and reserve are one transaction and the ORDER inside it is deliberate.</b> The
 * reservations go in FIRST, then the revocations. Reversed, there is a window in which a key is revoked
 * and its prefix is free — and the mint path consults the reservation table, not the key table, so a
 * mint landing in that window could take the prefix legitimately and the reservation would then fail on
 * a conflict it has no way to interpret. Reserving first makes the window harmless: the prefix is
 * unavailable from the first statement, and the key it belonged to is still live until the same
 * transaction commits.
 *
 * <p><b>It revokes through the repository, not through SQL.</b> {@code ApiKey}'s {@code @SQLDelete}
 * carries {@code and version = ?} plus {@code version = version + 1} (AGENTS §1's soft-delete rule), so
 * a hand-written {@code update … set deleted_at = now()} would skip the optimistic-lock check and
 * silently overwrite a concurrent rotation. The whole point of an extraction's key revocation is that
 * nothing survives it; a lost update is exactly the way one would.
 */
@Service
class ApiKeyRemintingService implements ApiKeyReminting {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRemintingService.class);

    private final ApiKeyRepository keys;
    private final ApiKeyPrefixReservations reservations;
    private final AuditLog auditLog;

    ApiKeyRemintingService(ApiKeyRepository keys, ApiKeyPrefixReservations reservations,
            AuditLog auditLog) {
        this.keys = keys;
        this.reservations = reservations;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional
    public Reminted revokeAndReserve(UUID orgId, String reason) {
        if (orgId == null) {
            throw new IllegalArgumentException("re-minting is scoped to one organization; null would"
                    + " select every PLATFORM key (org_id is null) and revoke the operator's own");
        }
        List<ApiKey> live = keys.findByOrgIdOrderByCreatedAtAsc(orgId);
        List<String> prefixes = live.stream().map(ApiKey::getPrefix).sorted().toList();
        reservations.reserve(prefixes, orgId, reason);
        live.forEach(keys::delete);
        if (!prefixes.isEmpty()) {
            // Recorded against the ORG, so the row lands in the tenant's own audit_log rather than the
            // platform's — the tenant's record that its credentials were retired on the way out.
            // AuditLogImpl names the schema from org_id (SplitTables.homeOf), so this is correct from
            // the platform axis the extraction runs on.
            //
            // WHETHER IT TRAVELS IS THE CALLER'S ORDERING, NOT THIS METHOD'S, and it was wrong once:
            // TenantBundler emitted item 8 last, after item 1 had already copied the tenant schema, so
            // a CUTOVER-extracted tenant arrived holding no record that its keys had been revoked —
            // silently, because the row was perfectly present at the origin, in the schema the origin
            // was about to drop. TenantBundler now re-mints BEFORE it reads the tenant schema and says
            // why at the call site; TenantBundleTest asserts the row reaches the bundle's sink. Move
            // that call back down and the audit trail stops travelling with no other symptom.
            auditLog.record("apikeys.keys_reminted_for_extraction", orgId, orgId.toString(),
                    "prefixes=" + String.join(",", prefixes), "revoked=" + prefixes.size());
            log.info("revoked {} API key(s) for organization {} and reserved their prefixes permanently:"
                    + " {}", prefixes.size(), orgId, prefixes);
        }
        return new Reminted(prefixes.size(), prefixes);
    }
}

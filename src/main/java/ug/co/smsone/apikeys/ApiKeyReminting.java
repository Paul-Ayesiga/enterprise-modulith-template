package ug.co.smsone.apikeys;

import java.util.List;
import java.util.UUID;

/**
 * <strong>ADR 0010 §6 item 8: "RE-MINTED {@code api_key} rows; the platform revokes the old ones and
 * PERMANENTLY RESERVES their prefixes."</strong> The one thing an extraction has to do to this module,
 * declared where the caller can see it.
 *
 * <p><b>Why the secret never travels, settled in §2.1.</b> One facet proposed splitting {@code api_key}
 * so the secret could live in the tenant schema and ride out in the dump. It was overruled, and the
 * deciding argument is this interface: the bundle re-mints anyway, so the secret never travels
 * <em>regardless</em>, and the split would have bought an extra index probe on every machine request
 * for nothing. The far side mints its own keys into its own prefix space and the tenant re-issues
 * them to its clients — a stated, one-time cost, and the only shape in which one credential is never
 * valid against two systems at once.
 *
 * <p><b>Why the prefix reservation is part of the same call.</b> Revoking is a soft delete, and
 * {@code uq_api_key_prefix_live} is partial on {@code deleted_at is null} — so revocation RELEASES the
 * prefix back into a 48-bit space ({@code sk_} + 12 hex, {@code ApiKeyHashing.mint}). Re-minting a
 * departed tenant's prefix for a different organization would make a stale credential from the old
 * tenant resolve against another tenant's key row on the one lookup that runs before any tenant is
 * known. The hash still has to match, so it is a collision and not a bypass — but ADR 0010 §2.1 keeps
 * this table platform-tier precisely because that lookup cannot be scoped, and "48 bits will not
 * collide" is a probability where a constraint will do. Revoke and reserve are therefore one operation,
 * in one transaction: a revocation whose reservation failed is the exact state the reservation exists
 * to prevent.
 *
 * <p>Declared in the module's API package, implemented in {@code internal} — the seam AGENTS §2.3
 * describes, and the same shape as {@code subscription.Subscriptions}. Nothing about the mint path,
 * the hashing or the entity escapes through it.
 */
public interface ApiKeyReminting {

    /**
     * What one organization's extraction did to its credentials.
     *
     * @param revoked how many live keys were revoked — zero is a legitimate answer and the manifest
     *     says so rather than treating it as a failure
     * @param reservedPrefixes the public halves that may never be minted again, sorted, so the operator
     *     can hand the tenant an exact list of what stopped working and when
     */
    record Reminted(int revoked, List<String> reservedPrefixes) {

        public Reminted {
            reservedPrefixes = List.copyOf(reservedPrefixes);
        }
    }

    /**
     * Revokes every live key of one organization and reserves their prefixes permanently.
     *
     * <p>Idempotent by construction: a second call finds no live keys, revokes nothing, and reserves
     * nothing new — the reservation rows are keyed on the prefix itself. That matters because an
     * extraction is a runbook step somebody re-runs after a failure, and a step that cannot be re-run
     * is a step that gets skipped.
     *
     * @param reason recorded on every reservation row, for the operator who finds a prefix refused
     *     years later and needs to know which extraction burned it
     */
    Reminted revokeAndReserve(UUID orgId, String reason);
}

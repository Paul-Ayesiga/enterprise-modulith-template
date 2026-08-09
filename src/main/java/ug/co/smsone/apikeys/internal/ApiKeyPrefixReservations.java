package ug.co.smsone.apikeys.internal;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * <strong>The prefixes that may never be minted again</strong> — {@code platform.api_key_prefix_
 * reservation} (V59), and the mint that consults it.
 *
 * <p>Reserving a prefix and then minting without checking would be a table with a primary key and no
 * effect, so both halves live here and there is exactly one mint in this module:
 * {@link #mintUnreservedPrefix()} is what {@code ApiKeyService} calls on all three of its paths
 * (create org key, create platform key, rotate). {@code ApiKeyHashing.mint} stays a pure function and
 * is not called anywhere else.
 *
 * <p><b>The retry loop, and why it is bounded and loud.</b> A collision against the reservation table
 * is astronomically unlikely — 48 bits of prefix against a reservation set that only grows when
 * tenants leave — so a loop that spun forever would hide a real fault (a reservation table that
 * somehow holds everything, a broken RNG) as a hang on the mint path. Ten attempts, then a refusal
 * that names the situation: the difference between "this is impossible" and "if it happens, say so"
 * is what turns a probability argument into an engineering one.
 *
 * <p>Platform tier, always. Every statement names {@code platform.} (AGENTS §1) because this is read
 * and written on paths where no tenant axis exists — a mint on the platform admin surface, and an
 * extraction running on the platform axis.
 */
@Component
class ApiKeyPrefixReservations {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyPrefixReservations.class);

    /** See the class note. Ten is not a tuning parameter; it is the point at which "impossible" is wrong. */
    private static final int MINT_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;

    ApiKeyPrefixReservations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A freshly minted key whose prefix is not reserved.
     *
     * @throws IllegalStateException after {@link #MINT_ATTEMPTS} collisions — see the class note
     */
    ApiKeyHashing.Minted mintUnreservedPrefix() {
        for (int attempt = 1; attempt <= MINT_ATTEMPTS; attempt++) {
            ApiKeyHashing.Minted minted = ApiKeyHashing.mint();
            if (!isReserved(minted.prefix())) {
                return minted;
            }
            log.warn("api key prefix {} is reserved by a previous tenant extraction; re-minting"
                    + " (attempt {} of {})", minted.prefix(), attempt, MINT_ATTEMPTS);
        }
        throw new IllegalStateException("could not mint an API key prefix that is not permanently"
                + " reserved after " + MINT_ATTEMPTS + " attempts. A prefix is 48 bits of randomness"
                + " and reservations only accumulate when tenants are extracted, so this is not a"
                + " collision — check platform.api_key_prefix_reservation for a row set that should"
                + " not be there, and check that ApiKeyHashing.mint is still producing random"
                + " prefixes (ADR 0010 §6 item 8).");
    }

    boolean isReserved(String prefix) {
        Boolean reserved = jdbc.queryForObject(
                "select exists (select 1 from platform.api_key_prefix_reservation where prefix = ?)",
                Boolean.class, prefix);
        return Boolean.TRUE.equals(reserved);
    }

    /**
     * Records the prefixes as permanently taken.
     *
     * <p>{@code on conflict do nothing}: re-running an extraction must not fail on a prefix it already
     * burned, and the first reservation's reason is the true one — a second attempt's is a duplicate
     * of the same event, not new information.
     */
    void reserve(List<String> prefixes, UUID orgId, String reason) {
        for (String prefix : prefixes) {
            jdbc.update("""
                    insert into platform.api_key_prefix_reservation (prefix, org_id, reserved_at, reason)
                    values (?, ?, now(), ?)
                    on conflict (prefix) do nothing
                    """, prefix, orgId, reason);
        }
    }
}

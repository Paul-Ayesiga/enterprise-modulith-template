package ug.co.smsone.access;

import java.time.Instant;
import java.util.UUID;

/**
 * A person revoked one of their devices. Revocation is a soft delete, so the {@code user_device} row
 * survives — this event is how everything hanging off that device learns it is no longer live.
 *
 * <p><b>It exists because V53 cut a foreign key that was doing security work.</b>
 * {@code user_device_trust.device_id → user_device(id) ON DELETE CASCADE} guaranteed a revoked device
 * left no trust grant behind; the device is PLATFORM-tier and the grant is TENANT-tier (ADR 0010 §2),
 * so the constraint could not survive the boundary. This is the fast half of its replacement —
 * {@code DeviceTrustRevocationListener} deletes the grants — and it is the half that still works once
 * the grants live in N tenant schemas, where a synchronous delete from the platform side cannot reach
 * them and only a per-tenant fan-out can.
 *
 * <p><b>Deliberately no {@code orgId}, and that is a departure worth naming.</b> Ten of this codebase's
 * domain events carry the org as their first component and ADR 0010 §3.2 leans on that (async listeners
 * take the tenant from the event, never from a thread-local). This one cannot: a device belongs to a
 * human, not to a tenant, and revoking it must reach EVERY organization that granted trust over it —
 * one, none, or all of them. The consumer is therefore cross-tenant by construction, which is exactly
 * why it is one place rather than scattered through the revocation paths.
 *
 * <p>{@code fingerprint} rides along for the log line and for any future consumer that keys on the
 * device as the client presents it; the grant deletion keys on {@code deviceId}, which is precise —
 * a re-registration of the same fingerprint is a different row and must not be caught in the sweep.
 */
public record DeviceRevoked(UUID personId, UUID deviceId, String fingerprint, Instant occurredAt) {
}

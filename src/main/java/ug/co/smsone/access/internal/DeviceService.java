package ug.co.smsone.access.internal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.access.DeviceRevoked;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/** Self-service device registration; re-registering the same fingerprint updates it, never dupes. */
@Service
class DeviceService {

    private static final List<String> KINDS = List.of("BROWSER", "MOBILE", "CLI");

    private final UserDeviceRepository devices;
    private final UserDeviceTrustRepository deviceTrust;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DeviceService(UserDeviceRepository devices, UserDeviceTrustRepository deviceTrust,
            ApplicationEventPublisher events, Clock clock) {
        this.devices = devices;
        this.deviceTrust = deviceTrust;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    UserDevice register(UUID personId, String name, String kind, String fingerprint, String pushToken) {
        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase();
        if (!KINDS.contains(normalizedKind)) {
            throw new ValidationException("kind must be BROWSER, MOBILE or CLI.",
                    ApiSource.pointer("/data/attributes/kind"));
        }
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 100) {
            throw new ValidationException("fingerprint is required (max 100 characters).",
                    ApiSource.pointer("/data/attributes/fingerprint"));
        }
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ValidationException("name is required (max 100 characters).",
                    ApiSource.pointer("/data/attributes/name"));
        }
        return devices.findByPersonIdAndFingerprint(personId, fingerprint.trim())
                .map(existing -> {
                    existing.update(name.trim(), pushToken);
                    return devices.save(existing);
                })
                .orElseGet(() -> devices.save(
                        UserDevice.register(personId, name.trim(), normalizedKind, fingerprint.trim(), pushToken)));
    }

    @Transactional(readOnly = true)
    Window<UserDevice> list(UUID personId, CursorPageRequest page) {
        return devices.pageByPersonId(personId, page);
    }

    /**
     * Revocation is a SOFT delete — the row stays as the trail (V31) — so nothing about the device row
     * itself tells a later reader that its trust grants are dead. Until V53 the foreign key did:
     * {@code user_device_trust.device_id → user_device ON DELETE CASCADE}. That FK crossed the tenant
     * boundary (ADR 0010 §2: the device is PLATFORM, the grant is TENANT) and had to go, so the event
     * below is what carries the news. {@code DeviceTrustRevocationListener} consumes it.
     *
     * <p>Published inside the transaction and delivered after it commits — Modulith writes the
     * publication to {@code event_publication} in this same transaction, so a crash between the soft
     * delete and the grant deletion is retried rather than lost. A repository delete fires no
     * {@code @DomainEvents}, hence the explicit publish (the same reason {@code MemberService} states).
     */
    @Transactional
    void revoke(UUID personId, UUID id) {
        UserDevice device = devices.findByIdAndPersonId(id, personId)
                .orElseThrow(() -> new NotFoundException("Device not found."));
        devices.delete(device);
        events.publishEvent(new DeviceRevoked(personId, device.getId(), device.getFingerprint(),
                clock.instant()));
    }

    @Transactional(readOnly = true)
    Window<UserDevice> listForPerson(UUID personId, CursorPageRequest page) {
        return devices.pageByPersonId(personId, page);
    }

    /**
     * Does THIS organization trust this person's device? Org-scoped since V51: trust used to be one
     * global boolean on the device row while {@code require_trusted_device} is per-org, so a device
     * blessed inside any org satisfied every org's policy for that person.
     */
    /** The subset of {@code deviceIds} this org trusts — one query, for rendering a page of devices. */
    @Transactional(readOnly = true)
    java.util.Set<UUID> trustedAmong(UUID orgId, java.util.Collection<UUID> deviceIds) {
        if (orgId == null || deviceIds == null || deviceIds.isEmpty()) {
            return java.util.Set.of();
        }
        return deviceTrust.trustedAmong(orgId, deviceIds);
    }

    /** Platform-support only: trusted by ANY org. See the repository method for why it is separate. */
    @Transactional(readOnly = true)
    java.util.Set<UUID> trustedByAnyOrgAmong(java.util.Collection<UUID> deviceIds) {
        return deviceIds == null || deviceIds.isEmpty()
                ? java.util.Set.of() : deviceTrust.trustedByAnyOrgAmong(deviceIds);
    }

    boolean isTrusted(UUID orgId, UUID personId, String fingerprint) {
        return orgId != null && deviceTrust.isTrusted(orgId, personId, fingerprint);
    }

    /** Own transaction: the enforcement filter (not transactional) calls this off the request path. */
    @Transactional
    public void stampLastSeen(UUID personId, String fingerprint, java.time.Instant now,
            java.time.Instant throttleBefore) {
        devices.touchThrottled(personId, fingerprint, now, throttleBefore);
    }
}


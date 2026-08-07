package ug.co.smsone.access.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/** Self-service device registration; re-registering the same fingerprint updates it, never dupes. */
@Service
class DeviceService {

    private static final List<String> KINDS = List.of("BROWSER", "MOBILE", "CLI");

    private final UserDeviceRepository devices;

    DeviceService(UserDeviceRepository devices) {
        this.devices = devices;
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

    @Transactional
    void revoke(UUID personId, UUID id) {
        UserDevice device = devices.findByIdAndPersonId(id, personId)
                .orElseThrow(() -> new NotFoundException("Device not found."));
        devices.delete(device);
    }

    @Transactional(readOnly = true)
    Window<UserDevice> listForPerson(UUID personId, CursorPageRequest page) {
        return devices.pageByPersonId(personId, page);
    }

    boolean isTrusted(UUID personId, String fingerprint) {
        return devices.isTrusted(personId, fingerprint);
    }

    /** Own transaction: the enforcement filter (not transactional) calls this off the request path. */
    @Transactional
    public void stampLastSeen(UUID personId, String fingerprint, java.time.Instant now,
            java.time.Instant throttleBefore) {
        devices.touchThrottled(personId, fingerprint, now, throttleBefore);
    }
}


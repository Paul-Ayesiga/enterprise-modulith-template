package ug.co.smsone.access.internal;

import java.time.Instant;
import ug.co.smsone.shared.web.ResourceObject;

final class DeviceResources {

    static final String RESOURCE_TYPE = "device";

    record DeviceAttributes(String name, String kind, String fingerprint, boolean trusted,
            boolean hasPushToken, Instant lastSeenAt, Instant createdAt) {
    }

    private DeviceResources() {
    }

    static ResourceObject toResource(UserDevice device) {
        return new ResourceObject(device.getId().toString(), RESOURCE_TYPE,
                new DeviceAttributes(device.getName(), device.getKind(), device.getFingerprint(),
                        device.isTrusted(), device.getPushToken() != null, device.getLastSeenAt(),
                        device.getCreatedAt()));
    }
}

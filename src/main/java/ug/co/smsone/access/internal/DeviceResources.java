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

    /**
     * {@code trusted} is answered FOR ONE ORGANIZATION and must be passed in, not read off the device.
     * Since V51 trust is a grant by an org over a device ({@code user_device_trust}), so the same device
     * is legitimately trusted in one of a person\'s orgs and not another — there is no device-wide
     * answer to render, and the boolean that used to be here was the bug.
     */
    static ResourceObject toResource(UserDevice device, boolean trustedByThisOrg) {
        return new ResourceObject(device.getId().toString(), RESOURCE_TYPE,
                new DeviceAttributes(device.getName(), device.getKind(), device.getFingerprint(),
                        trustedByThisOrg, device.getPushToken() != null, device.getLastSeenAt(),
                        device.getCreatedAt()));
    }
}

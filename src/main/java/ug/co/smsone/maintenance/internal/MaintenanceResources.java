package ug.co.smsone.maintenance.internal;

import java.time.Instant;
import ug.co.smsone.shared.web.ResourceObject;

final class MaintenanceResources {

    static final String RESOURCE_TYPE = "maintenance-window";

    record WindowAttributes(String orgId, Instant startsAt, Instant endsAt, String mode, String message) {
    }

    private MaintenanceResources() {
    }

    static ResourceObject toResource(MaintenanceWindow window) {
        return new ResourceObject(window.getId().toString(), RESOURCE_TYPE,
                new WindowAttributes(window.getOrgId() == null ? null : window.getOrgId().toString(),
                        window.getStartsAt(), window.getEndsAt(), window.getMode(), window.getMessage()));
    }
}

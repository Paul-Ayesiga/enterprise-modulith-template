package ug.co.smsone.integration.internal;

import java.util.Map;
import ug.co.smsone.shared.web.ResourceObject;

final class IntegrationResources {

    static final String RESOURCE_TYPE = "integration";

    record IntegrationAttributes(String kind, String provider, boolean enabled,
            Map<String, String> settings) {
    }

    private IntegrationResources() {
    }

    static ResourceObject toResource(IntegrationService.View view) {
        return new ResourceObject(view.id().toString(), RESOURCE_TYPE,
                new IntegrationAttributes(view.kind(), view.provider(), view.enabled(), view.settings()));
    }
}

package ug.co.smsone.organization.internal;

import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The fixed permission catalog — the vocabulary clients use when composing custom roles. Read-only and
 * available to any authenticated user (no org scope; the catalog is global).
 */
@RestController
@RequestMapping("/api/v1/permissions")
class PermissionCatalogController {

    private static final String RESOURCE_TYPE = "permission";

    record PermissionAttributes(String name, String code) {
    }

    @GetMapping
    List<ResourceObject> list() {
        return Arrays.stream(Permission.values())
                .map(permission -> new ResourceObject(permission.code(), RESOURCE_TYPE,
                        new PermissionAttributes(permission.name(), permission.code())))
                .toList();
    }
}

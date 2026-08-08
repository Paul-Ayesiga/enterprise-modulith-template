package ug.co.smsone.access.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Support's read of a user's devices — the context a ticket or oversight session starts from.
 *
 * <p><b>It straddles the tenancy boundary, and therefore takes two pinned spans rather than one.</b>
 * {@code user_device} is platform-tier and {@code user_device_trust} is the tenant's (ADR 0010 §2), and
 * the caller here is a platform operator with no organization at all — so the request arrives on the
 * PLATFORM axis {@code CurrentUserFilter} installed, which can read the devices and cannot see a single
 * grant. Widening the whole handler to a tenant axis would be the wrong repair twice over: the device
 * rows would then be looked for in a tenant's schema, and there is no one tenant to name because the
 * question this endpoint asks is deliberately cross-tenant.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminDeviceController {

    /**
     * The org whose axis the trust half borrows. It names no organization deliberately: an org that has
     * never been promoted resolves to the shared {@code tenant_pool}, and a UUID in no
     * {@code organization} row can never resolve to anything else — so this IS the pooled schema's axis,
     * spelled with the only vocabulary {@code TenantContext} has. Same constant, same reasoning as
     * {@code MappedSchemaValidator} and {@code WebhookSecretEncryptionMigrator}.
     *
     * <p>When silos exist (ADR 0010 Phase 5) one query stops being able to answer this: the loop belongs
     * here, over {@code platform.tenant_placement}, unioning the trusted ids each home reports.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final DeviceService devices;

    AdminDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @GetMapping("/users/{personId}/devices")
    @Operation(summary = "List a user's devices as the platform",
            description = "`trusted` here means trusted by AT LEAST ONE organization. This view has no "
                    + "organization in context, unlike the tenant-facing listing where the flag is "
                    + "answered for one org; since V51 trust is granted per organization, so there is "
                    + "no single device-wide answer to report.")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(@PathVariable UUID personId, CursorPageRequest page) {
        // Span one, on the caller's PLATFORM axis: the devices themselves are platform-tier, because a
        // human's devices are a property of the human and not of any tenant they happen to be seated in.
        var window = devices.listForPerson(personId, page);
        var deviceIds = window.getContent().stream().map(UserDevice::getId).toList();
        // Span two, on a tenant axis: the grants are the tenants' rows. Pinned around the call and not
        // inside DeviceService, because that method is @Transactional and TenantContext refuses a pin
        // once a connection is bound — the schema is chosen at borrow, so pinning inside would silently
        // do nothing.
        var trusted = TenantContext.callAs(POOLED_TENANT, () -> devices.trustedByAnyOrgAmong(deviceIds));
        return WindowedResult.of(window, page,
                device -> DeviceResources.toResource(device, trusted.contains(device.getId())));
    }
}

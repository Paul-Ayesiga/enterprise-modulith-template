package ug.co.smsone.access.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/** Support's read of a user's devices — the context a ticket or oversight session starts from. */
@RestController
@RequestMapping("/api/v1/admin")
class AdminDeviceController {

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
        var window = devices.listForPerson(personId, page);
        var trusted = devices.trustedByAnyOrgAmong(
                window.getContent().stream().map(UserDevice::getId).toList());
        return WindowedResult.of(window, page,
                device -> DeviceResources.toResource(device, trusted.contains(device.getId())));
    }
}

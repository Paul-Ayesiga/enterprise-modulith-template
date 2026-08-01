package ug.co.smsone.access.internal;

import io.swagger.v3.oas.annotations.Operation;
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

    @GetMapping("/users/{subject}/devices")
    @Operation(summary = "List a user's devices as the platform")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(@PathVariable String subject, CursorPageRequest page) {
        return WindowedResult.of(devices.listForUser(subject, page), page, DeviceResources::toResource);
    }
}

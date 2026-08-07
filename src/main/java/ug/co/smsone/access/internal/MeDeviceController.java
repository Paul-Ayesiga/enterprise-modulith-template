package ug.co.smsone.access.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The caller's own devices. Registering is idempotent per fingerprint (the {@code X-Device-Id});
 * revoking soft-deletes. Marking a device TRUSTED is deliberately NOT here — trust is an
 * organization's grant, not a self-claim (a security policy can require it).
 */
@RestController
@RequestMapping("/api/v1/me/devices")
class MeDeviceController {

    private final DeviceService devices;

    MeDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    record RegisterRequest(String name, String kind, String fingerprint, String pushToken) {
    }

    @PostMapping
    @Operation(summary = "Register (or update) a device",
            description = "Idempotent per `fingerprint` — the same device re-registers rather than duplicating.")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject register(@RequestBody RegisterRequest request, CurrentUser user) {
        // A freshly registered device is trusted by nobody: trust is a grant an ORG makes (V51), and
        // registering is the person asserting the device exists, not the org vouching for it.
        return DeviceResources.toResource(devices.register(requirePerson(user), request.name(),
                request.kind(), request.fingerprint(), request.pushToken()), false);
    }

    @GetMapping
    @Operation(summary = "List your devices",
            description = "`trusted` is answered for the organization your token is scoped to — the "
                    + "same device can be trusted in one of your organizations and not another.")
    WindowedResult<ResourceObject> list(CurrentUser user, CursorPageRequest page) {
        var window = devices.list(requirePerson(user), page);
        var trusted = devices.trustedAmong(user.organizationId(),
                window.getContent().stream().map(UserDevice::getId).toList());
        return WindowedResult.of(window, page,
                device -> DeviceResources.toResource(device, trusted.contains(device.getId())));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id, CurrentUser user) {
        devices.revoke(requirePerson(user), id);
    }

    /**
     * A device belongs to a person. A machine is refused rather than defaulted: an API key is not a
     * device and has no person to hang one off, and {@code user_device.person_id} is NOT NULL (V31),
     * so a null would be a constraint violation at flush instead of a 403 at the door.
     */
    private static UUID requirePerson(CurrentUser user) {
        if (user.personId() == null) {
            throw new ForbiddenException("Devices belong to a person; this caller is not one.");
        }
        return user.personId();
    }
}

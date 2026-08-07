package ug.co.smsone.compliance.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The caller's own compliance surface: consent (record a grant or withdrawal — append-only), a
 * data export of their record (privacy/portability), and requesting their own erasure.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeComplianceController {

    private final ComplianceService compliance;

    MeComplianceController(ComplianceService compliance) {
        this.compliance = compliance;
    }

    record ConsentRequest(String purpose, boolean granted, String source) {
    }

    record ConsentAttributes(String purpose, boolean granted, String source, java.time.Instant at) {
    }

    @GetMapping("/consents")
    @Operation(summary = "Your consent history (append-only, newest first)")
    List<ResourceObject> consents(CurrentUser user) {
        UUID personId = requirePerson(user);
        return compliance.consentHistory(personId).stream()
                .map(c -> new ResourceObject(personId.toString(), "consent",
                        new ConsentAttributes(c.getPurpose(), c.isGranted(), c.getSource(), c.getCreatedAt())))
                .toList();
    }

    @PostMapping("/consents")
    @Operation(summary = "Record a consent decision",
            description = "A withdrawal is `granted:false` — a new row, never an overwrite.")
    ResourceObject record(@RequestBody ConsentRequest request, CurrentUser user) {
        UUID personId = requirePerson(user);
        var saved = compliance.recordConsent(personId, request.purpose(), request.granted(),
                request.source() == null ? "api" : request.source());
        return new ResourceObject(personId.toString(), "consent",
                new ConsentAttributes(saved.getPurpose(), saved.isGranted(), saved.getSource(), saved.getCreatedAt()));
    }

    @GetMapping("/data-export")
    @Operation(summary = "Export your own data (portability)",
            description = "Your profile-linked compliance record — consents and hold status.")
    Map<String, Object> export(CurrentUser user) {
        return compliance.dataExport(requirePerson(user));
    }

    @PostMapping("/erasure-request")
    @Operation(summary = "Request erasure of your data (GDPR art. 17)",
            description = """
                    Soft-deletes your data immediately (invisible at once); hard erasure follows at \
                    the retention window. REFUSED while a legal hold is in force.""")
    ResourceObject requestErasure(CurrentUser user) {
        UUID personId = requirePerson(user);
        // Self-service: the person and the requester are the same human, and erasure_request keeps both
        // columns anyway so an admin-initiated one is distinguishable at a glance (V34).
        ErasureRequest request = compliance.requestErasure(personId, personId);
        return new ResourceObject(request.getId().toString(), "erasure-request",
                Map.of("status", request.getStatus(), "detail", request.getDetail() == null ? "" : request.getDetail()));
    }

    /**
     * Every endpoint here is about a HUMAN: consent is given by a person and erasure is a person's
     * right. A machine key authenticates fine and is nobody — {@code personId} is null for an API key
     * and permanently so (V10 reserves {@code API_KEY} unused precisely to avoid manufacturing a person
     * per robot) — so it is refused here, with a message that names the credential rather than letting a
     * null reach a NOT NULL {@code person_id} and surface as a 500.
     */
    private static UUID requirePerson(CurrentUser user) {
        UUID personId = user.personId();
        if (personId == null) {
            throw new ForbiddenException("This endpoint acts for a person and the caller is not one. "
                    + "Call it with a signed-in user's token, not an API key.");
        }
        return personId;
    }
}

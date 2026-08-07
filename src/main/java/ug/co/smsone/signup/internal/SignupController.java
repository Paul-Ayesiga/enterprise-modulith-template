package ug.co.smsone.signup.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The public front door (no authentication — permitted in the security config; the shared per-IP
 * rate limit still applies). Two steps: request (always 202, enumeration-safe) and verify (the
 * emailed link — GET so the link itself works, POST for programmatic clients).
 */
@RestController
@RequestMapping("/api/v1/signup")
@Tag(name = "Public · Signup")
class SignupController {

    static final String RESOURCE_TYPE = "signup";

    private final SignupService service;

    SignupController(SignupService service) {
        this.service = service;
    }

    /**
     * {@code givenName} / {@code familyName}, not first/last, on the PUBLIC body too: the row this
     * request becomes is a person (V10), name order is cultural, and the platform's own vocabulary
     * applies everywhere above the Keycloak adapter. Both optional — a mononym is an ordinary name.
     */
    record SignupBody(@NotBlank String organizationName, @NotBlank String email,
            String givenName, String familyName) {
    }

    record SignupAttributes(String message, String organizationId, String alias) {
    }

    @PostMapping
    @Operation(summary = "Start a self-service signup",
            description = """
                    Sends a verification email for creating a new organization. Always answers 202 \
                    with the same message whether or not the address is known anywhere — the email \
                    is the only channel that learns more. The link is single-use and expires; a new \
                    request supersedes a pending one for the same address. Disabled platforms \
                    answer 403 (SIGNUP_ENABLED).""")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResourceObject request(@jakarta.validation.Valid @RequestBody SignupBody body) {
        service.request(body.organizationName(), body.email(), body.givenName(), body.familyName());
        return new ResourceObject(UUID.randomUUID().toString(), RESOURCE_TYPE, new SignupAttributes(
                "Check your inbox — a verification link is on its way if the address is deliverable.",
                null, null));
    }

    @GetMapping("/verify")
    @Operation(summary = "Complete a signup from the emailed link",
            description = """
                    Spends the single-use token: the organization is created through the standard \
                    provisioning path — the owner receives the set-password invite, the trial (when \
                    enabled) starts, and billing auto-provisions. Invalid, spent, and expired tokens \
                    all answer the same 422.""")
    ResourceObject verifyGet(@RequestParam("token") String token) {
        return completed(service.verify(token));
    }

    record VerifyBody(@NotBlank String token) {
    }

    @PostMapping("/verify")
    @Operation(summary = "Complete a signup (programmatic form)")
    ResourceObject verifyPost(@jakarta.validation.Valid @RequestBody VerifyBody body) {
        return completed(service.verify(body.token()));
    }

    private static ResourceObject completed(SignupRequest request) {
        return new ResourceObject(request.getOrgId().toString(), RESOURCE_TYPE, new SignupAttributes(
                "Organization created. Check your email for the set-password invite to sign in as its owner.",
                request.getOrgId().toString(), null));
    }
}

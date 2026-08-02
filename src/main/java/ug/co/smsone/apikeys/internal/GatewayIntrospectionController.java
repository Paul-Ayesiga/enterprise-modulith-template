package ug.co.smsone.apikeys.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.ApiKeyAuthenticator;
import ug.co.smsone.shared.security.GatewaySecretVerifier;

/**
 * The API gateway's key-introspection seam — a MACHINE endpoint ({@code @Hidden}: it belongs in no
 * tag), permit-listed in {@code SecurityConfig} and authenticated here by the shared gateway secret
 * (constant-time compare). The gateway calls it to resolve a presented {@code X-Api-Key} to a
 * principal WITHOUT owning the key store; the modulith answers RFC 7662-style ({@code active} plus
 * subject/tenant/scopes). It is backed by the same {@link ApiKeyAuthenticator} the modulith's own
 * key filter uses, so the gateway and the service agree on what a key means.
 */
@Hidden
@RestController
@RequestMapping("/internal/gateway/api-key")
class GatewayIntrospectionController {

    private final ApiKeyAuthenticator authenticator;
    private final GatewaySecretVerifier secretVerifier;

    GatewayIntrospectionController(ApiKeyAuthenticator authenticator, GatewaySecretVerifier secretVerifier) {
        this.authenticator = authenticator;
        this.secretVerifier = secretVerifier;
    }

    record IntrospectionRequest(String apiKey) {
    }

    record IntrospectionResponse(boolean active, String subject, String tenant, Set<String> scopes) {
    }

    @PostMapping("/introspect")
    IntrospectionResponse introspect(
            @RequestHeader(name = "X-Gateway-Secret", required = false) String presentedSecret,
            @RequestBody IntrospectionRequest request) {
        secretVerifier.verify(presentedSecret);
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return inactive();
        }
        return authenticator.authenticate(request.apiKey())
                .map(principal -> new IntrospectionResponse(true, principal.subject(),
                        principal.orgId() == null ? null : principal.orgId().toString(),
                        principal.permissions()))
                .orElseGet(GatewayIntrospectionController::inactive);
    }

    private static IntrospectionResponse inactive() {
        return new IntrospectionResponse(false, null, null, Set.of());
    }
}

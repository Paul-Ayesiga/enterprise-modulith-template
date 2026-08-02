package ug.co.smsone.subscription.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.GatewaySecretVerifier;
import ug.co.smsone.subscription.EntitlementKeys;
import ug.co.smsone.subscription.Entitlements;

/**
 * The API gateway's quota seam — a MACHINE endpoint ({@code @Hidden}), permit-listed in
 * {@code SecurityConfig} and authenticated by the shared gateway secret (constant-time compare). Given a
 * consumer (an organization id), it returns that org's edge quota straight from its subscription plan
 * ({@link Entitlements#limitOf} on {@link EntitlementKeys#API_REQUESTS_PER_MINUTE}); a {@code limit} of
 * {@code -1} means the plan sets no ceiling. The gateway counts calls against this limit on Valkey.
 */
@Hidden
@RestController
@RequestMapping("/internal/gateway/quota")
class GatewayQuotaController {

    private final Entitlements entitlements;
    private final GatewaySecretVerifier secretVerifier;

    GatewayQuotaController(Entitlements entitlements, GatewaySecretVerifier secretVerifier) {
        this.entitlements = entitlements;
        this.secretVerifier = secretVerifier;
    }

    record QuotaResponse(long limit, long windowSeconds) {
    }

    @GetMapping
    QuotaResponse quota(@RequestHeader(name = "X-Gateway-Secret", required = false) String presentedSecret,
            @RequestParam String consumer) {
        secretVerifier.verify(presentedSecret);
        UUID orgId = parseOrg(consumer);
        Long limit = orgId == null ? null : entitlements.limitOf(orgId, EntitlementKeys.API_REQUESTS_PER_MINUTE);
        return limit == null ? new QuotaResponse(-1, 0) : new QuotaResponse(limit, 60);
    }

    private static UUID parseOrg(String consumer) {
        if (consumer == null || consumer.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(consumer);
        } catch (IllegalArgumentException notAUuid) {
            return null; // a non-UUID consumer has no org plan → unlimited
        }
    }

}

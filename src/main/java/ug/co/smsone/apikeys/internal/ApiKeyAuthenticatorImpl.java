package ug.co.smsone.apikeys.internal;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.security.ApiKeyAuthenticator;
import ug.co.smsone.shared.security.ApiKeyPrincipal;

/**
 * Verifies a presented {@code sk_<prefix>.<secret>}: split, look up the LIVE row by prefix,
 * constant-time compare the secret hash, reject if expired, then build the principal from the
 * row's stored authority (never the presented value). Usage is stamped throttled — the auth path
 * must stay a read.
 */
@Component
class ApiKeyAuthenticatorImpl implements ApiKeyAuthenticator {

    private static final Duration TOUCH_THROTTLE = Duration.ofMinutes(1);

    private final ApiKeyRepository keys;
    private final Clock clock;

    ApiKeyAuthenticatorImpl(ApiKeyRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<ApiKeyPrincipal> authenticate(String presentedKey) {
        int dot = presentedKey.indexOf('.');
        if (dot <= 0 || dot == presentedKey.length() - 1) {
            return Optional.empty(); // not the sk_<prefix>.<secret> shape
        }
        String prefix = presentedKey.substring(0, dot);
        String secret = presentedKey.substring(dot + 1);
        ApiKey key = keys.findByPrefix(prefix).orElse(null);
        if (key == null) {
            return Optional.empty();
        }
        byte[] presentedHash = ApiKeyHashing.sha256(secret).getBytes(StandardCharsets.UTF_8);
        byte[] storedHash = key.getSecretHash().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presentedHash, storedHash) || key.isExpired(clock.instant())) {
            return Optional.empty();
        }
        keys.touchThrottled(key.getId(), clock.instant(), clock.instant().minus(TOUCH_THROTTLE));
        Set<String> permissions = key.getPermissions() == null || key.getPermissions().isBlank()
                ? Set.of()
                : java.util.Arrays.stream(key.getPermissions().split(","))
                        .map(String::trim).filter(code -> !code.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new ApiKeyPrincipal(key.getId(), key.getName(), key.getOrgId(),
                permissions, key.getPlatformTier()));
    }
}

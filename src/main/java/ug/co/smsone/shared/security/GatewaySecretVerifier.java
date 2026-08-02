package ug.co.smsone.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.UnauthorizedException;

/**
 * Verifies the single shared secret the API gateway presents on every internal seam — key introspection,
 * audit ingest, quota lookup. It is ONE trust relationship (the edge authenticating to the platform), so
 * it is ONE secret ({@code app.gateway.secret}), checked in one place. Constant-time compare; a mismatch,
 * a missing header, or an unset secret is 401.
 */
@Component
public class GatewaySecretVerifier {

    private final byte[] expected;

    GatewaySecretVerifier(@Value("${app.gateway.secret:}") String secret) {
        this.expected = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Throws {@link UnauthorizedException} unless {@code presented} matches the configured gateway secret. */
    public void verify(String presented) {
        if (expected.length == 0 || presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected)) {
            throw new UnauthorizedException("Invalid gateway secret.");
        }
    }
}

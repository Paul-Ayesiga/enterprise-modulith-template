package ug.co.smsone.apikeys.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Prefix + secret minting and SHA-256 hashing. No salt: the secret is 256 bits of entropy already. */
final class ApiKeyHashing {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    record Minted(String prefix, String secret, String presented, String secretHash) {
    }

    private ApiKeyHashing() {
    }

    static Minted mint() {
        String prefix = "sk_" + randomHex(6);        // 12 hex chars of public id
        String secret = randomHex(24);               // 48 hex chars of secret
        String presented = prefix + "." + secret;    // what the caller sends as X-Api-Key
        return new Minted(prefix, secret, presented, sha256(secret));
    }

    static String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HEX.formatHex(buffer);
    }
}

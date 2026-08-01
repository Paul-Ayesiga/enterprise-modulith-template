package ug.co.smsone.integration.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for integration secret values — the same technique as the webhook signing-secret
 * cipher, kept module-local (its own key property) so the hub owns its encryption with zero blast
 * radius into webhooks. Stored form {@code enc:v1:<b64(iv||ct+tag)>}; a value already without the
 * prefix is passed through, so a plaintext row (or a non-secret value) is never mangled.
 */
@Component
class IntegrationSecretCipher {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    IntegrationSecretCipher(IntegrationProperties properties) {
        this.key = new SecretKeySpec(sha256(properties.secretEncryptionKey()), "AES");
    }

    String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer packed = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext);
            return PREFIX + Base64.getEncoder().encodeToString(packed.array());
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Integration secret encryption failed", ex);
        }
    }

    String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Integration secret decryption failed — check "
                    + "app.integration.secret-encryption-key", ex);
        }
    }

    private static byte[] sha256(String passphrase) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

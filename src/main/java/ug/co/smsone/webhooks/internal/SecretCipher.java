package ug.co.smsone.webhooks.internal;

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
 * At-rest encryption for signing secrets — AES-256-GCM, key derived (SHA-256) from
 * {@code app.webhooks.secret-encryption-key}. Encryption, NOT hashing, on purpose: the server must
 * recover the plaintext to compute each delivery's HMAC, so a hash would break signing; what this
 * buys is that a database dump alone no longer yields every tenant's signing secret. Stored form is
 * {@code enc:v1:<b64(iv || ciphertext+tag)>}; {@link #decrypt} passes through values without the
 * prefix so pre-encryption rows keep working until the startup migrator rewrites them.
 */
@Component
class SecretCipher {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(WebhookProperties properties) {
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
            throw new IllegalStateException("Webhook secret encryption failed", ex);
        }
    }

    String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored; // pre-encryption row — the migrator rewrites these at startup
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
            // A wrong key or corrupted value must fail the DELIVERY loudly, not sign garbage.
            throw new IllegalStateException("Webhook secret decryption failed — check "
                    + "app.webhooks.secret-encryption-key matches the key the secret was stored under", ex);
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

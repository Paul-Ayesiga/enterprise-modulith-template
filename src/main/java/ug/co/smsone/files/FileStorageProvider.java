package ug.co.smsone.files;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * Storage abstraction other modules depend on — never on the S3 SDK directly. Keys are
 * caller-scoped paths like {@code invoices/2026/07/inv-123.pdf}.
 */
public interface FileStorageProvider {

    void put(String key, InputStream content, long contentLength, String contentType);

    /** Multipart upload for large payloads; use for anything beyond a few MB. */
    void putLarge(String key, InputStream content, long contentLength, String contentType);

    InputStream get(String key);

    boolean exists(String key);

    void delete(String key);

    URL presignGet(String key, Duration ttl);

    URL presignPut(String key, String contentType, Duration ttl);
}

package ug.co.smsone.files;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * Storage abstraction other modules depend on — never on the S3 SDK directly. Keys are
 * caller-scoped paths like {@code invoices/2026/07/inv-123.pdf}.
 *
 * <h2>The three methods that exist for moving a tenant, and not for serving a request</h2>
 *
 * <p>{@link #list}, {@link #open} and {@link #write} are the bulk-copy triple ADR 0010 §6 bundle item 7
 * needs: <em>find</em> every object under one organization's prefix, <em>read</em> one faithfully, and
 * <em>put</em> it back on the other side. No request path uses them — the product surfaces address one
 * known key at a time and presign rather than stream — and that is why they were absent until an
 * extraction needed them. They are stated in the port rather than reached for through the S3 client
 * because the module's rule has not changed: SDK types never leave {@code files}.
 */
public interface FileStorageProvider {

    void put(String key, InputStream content, long contentLength, String contentType);

    /** Multipart upload for large payloads; use for anything beyond a few MB. */
    void putLarge(String key, InputStream content, long contentLength, String contentType);

    /**
     * One page of keys under {@code prefix}, lexicographically ordered, strictly after
     * {@code startAfter} ({@code null} starts at the beginning of the prefix).
     *
     * <p>The prefix is matched as a literal string and nothing about it is interpreted, so a caller
     * asking for {@code doc/o/<orgId>/} gets that organization's objects and no others — including
     * none of {@code doc/o/<orgId-with-a-longer-id>/}, as long as the caller ends the prefix with the
     * separator. Ending it anywhere else is the caller's bug and the store cannot see it.
     */
    ObjectPage list(String prefix, String startAfter, int maxKeys);

    /**
     * Opens an object together with the metadata a faithful copy has to preserve — one round trip, since
     * the store returns the type and the length in the same response as the bytes. The caller closes the
     * returned object; see {@link StoredObject} for why the stream is not simply an {@code InputStream}.
     *
     * @throws FileNotFoundException if the key holds no object
     */
    StoredObject open(String key);

    /**
     * Stores an object exactly as {@link #open} produced it, choosing the single-shot or multipart path
     * by size. The choice lives here on purpose: the threshold is storage configuration with its reason
     * written beside it, and a caller outside this module cannot see it — one that guessed would either
     * multipart every small object or single-shot a multi-gigabyte export.
     */
    void write(StoredObject object);

    InputStream get(String key);

    boolean exists(String key);

    void delete(String key);

    URL presignGet(String key, Duration ttl);

    URL presignPut(String key, String contentType, Duration ttl);
}

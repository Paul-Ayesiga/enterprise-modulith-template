package ug.co.smsone.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import ug.co.smsone.files.FileNotFoundException;
import ug.co.smsone.files.FileStorageException;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.files.ObjectPage;
import ug.co.smsone.files.StoredObject;

@Component
class S3StorageProvider implements FileStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

    /** S3 minimum part size is 5 MiB (except the last part). */
    private static final int PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    S3StorageProvider(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    // The breaker guards REMOTE calls only — presigning is local crypto and must not
    // mask (or be blocked by) storage outages.

    @Override
    @CircuitBreaker(name = "storage")
    public void put(String key, InputStream content, long contentLength, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
        } catch (S3Exception e) {
            throw new FileStorageException("put failed for key " + key, e);
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public void putLarge(String key, InputStream content, long contentLength, String contentType) {
        String uploadId = null;
        try {
            uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType)
                    .build()).uploadId();
            List<CompletedPart> parts = new ArrayList<>();
            byte[] buffer = new byte[PART_SIZE_BYTES];
            int partNumber = 1;
            int read;
            while ((read = readFully(content, buffer)) > 0) {
                byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                String etag = s3.uploadPart(UploadPartRequest.builder()
                                .bucket(properties.bucket())
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .build(),
                        RequestBody.fromBytes(chunk)).eTag();
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                partNumber++;
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (S3Exception | IOException e) {
            abortQuietly(key, uploadId);
            throw new FileStorageException("multipart upload failed for key " + key, e);
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public ObjectPage list(String prefix, String startAfter, int maxKeys) {
        if (maxKeys <= 0) {
            throw new FileStorageException("a listing page size must be positive, got " + maxKeys);
        }
        try {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(properties.bucket())
                    .prefix(prefix)
                    .maxKeys(maxKeys);
            if (startAfter != null && !startAfter.isBlank()) {
                request.startAfter(startAfter);
            }
            ListObjectsV2Response response = s3.listObjectsV2(request.build());
            // isTruncated is a boxed Boolean and an S3-compatible store may omit it; absent means "this
            // is all of it", which is the safe reading only because the caller pages on THIS flag — an
            // absent flag read as "more" would loop forever on a store that never sets it.
            return new ObjectPage(response.contents().stream().map(S3Object::key).toList(),
                    Boolean.TRUE.equals(response.isTruncated()));
        } catch (S3Exception e) {
            throw new FileStorageException("list failed for prefix " + prefix, e);
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public StoredObject open(String key) {
        try {
            // The response object carries the type and the length, so this is ONE round trip where a
            // head-then-get would be two — and two would also be a lie, since the object could change
            // between them and the copy would be written with the previous version's metadata.
            ResponseInputStream<GetObjectResponse> stream = s3.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            GetObjectResponse metadata = stream.response();
            return new StoredObject(key, metadata.contentType(),
                    metadata.contentLength() == null ? 0L : metadata.contentLength(), stream);
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException("no object for key " + key, e);
        } catch (S3Exception e) {
            throw new FileStorageException("open failed for key " + key, e);
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public void write(StoredObject object) {
        // Annotated even though both branches are annotated too: this delegates through `this`, which is
        // self-invocation and bypasses the proxy entirely (AGENTS §4.3), so without it a bulk restore
        // would run with no breaker at all. With it, one logical write counts once — which is what the
        // breaker's failure rate should be measuring.
        if (object.sizeBytes() > properties.multipartThreshold().toBytes()) {
            putLarge(object.key(), object.content(), object.sizeBytes(), object.contentType());
        } else {
            put(object.key(), object.content(), object.sizeBytes(), object.contentType());
        }
    }

    /**
     * Best-effort: an un-aborted multipart upload retains (and bills) every uploaded part server-side
     * indefinitely, and no lifecycle rule cleans them up. Abort failure is logged, never rethrown —
     * the upload failure itself is what the caller must see.
     */
    private void abortQuietly(String key, String uploadId) {
        if (uploadId == null) {
            return;
        }
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (RuntimeException abortFailure) {
            log.warn("Could not abort multipart upload {} for key {} — parts remain until manual cleanup: {}",
                    uploadId, key, abortFailure.toString());
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public InputStream get(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            // distinct type: a business not-found must never trip the breaker (ignore-exceptions)
            throw new FileNotFoundException("no object for key " + key, e);
        } catch (S3Exception e) {
            throw new FileStorageException("get failed for key " + key, e);
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new FileStorageException("head failed for key " + key, e);
        }
    }

    @Override
    @CircuitBreaker(name = "storage")
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
        } catch (S3Exception e) {
            // the port's contract: SDK types never cross the module boundary (AGENTS §2.3)
            throw new FileStorageException("delete failed for key " + key, e);
        }
    }

    @Override
    public URL presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .build())
                .build()).url();
    }

    @Override
    public URL presignPut(String key, String contentType, Duration ttl) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .build())
                .build()).url();
    }

    /** Fills the buffer as far as the stream allows; returns bytes read (0 at EOF). */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }
}

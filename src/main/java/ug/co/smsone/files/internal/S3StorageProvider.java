package ug.co.smsone.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import ug.co.smsone.files.FileStorageException;
import ug.co.smsone.files.FileStorageProvider;

@Component
@CircuitBreaker(name = "storage")
class S3StorageProvider implements FileStorageProvider {

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

    @Override
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
    public void putLarge(String key, InputStream content, long contentLength, String contentType) {
        String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .build()).uploadId();
        try {
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
            throw new FileStorageException("multipart upload failed for key " + key, e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new FileStorageException("no object for key " + key, e);
        }
    }

    @Override
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
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
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

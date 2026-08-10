package ug.co.smsone.files.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Idempotently creates THIS DEPLOYMENT's bucket at startup (disable via app.storage.bootstrap-bucket).
 *
 * <p>The bucket it creates is {@link DeploymentBucket}'s, never {@code StorageProperties.bucket()} —
 * which is what makes an extracted deployment's first boot produce its own container rather than
 * finding the platform's already there and quietly writing into it (ADR 0010 §6 hop 2→3).
 */
@Component
class BucketBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketBootstrap.class);

    private final S3Client s3;
    private final StorageProperties properties;
    private final DeploymentBucket bucket;

    BucketBootstrap(S3Client s3, StorageProperties properties, DeploymentBucket bucket) {
        this.s3 = s3;
        this.properties = properties;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.bootstrapBucket()) {
            return;
        }
        // Declares the platform axis (ADR 0010 §3.4). Object storage only — no table is touched here —
        // but every ApplicationRunner in this codebase declares one, so that the invariant is
        // "a background entry point states its axis" rather than "…unless someone judged it unnecessary".
        TenantContext.runAsPlatform(() -> {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket.name()).build());
            } catch (NoSuchBucketException e) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket.name()).build());
                log.info("Created storage bucket '{}'", bucket.name());
            }
        });
    }
}

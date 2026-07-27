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

/** Idempotently creates the configured bucket at startup (disable via app.storage.bootstrap-bucket). */
@Component
class BucketBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketBootstrap.class);

    private final S3Client s3;
    private final StorageProperties properties;

    BucketBootstrap(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.bootstrapBucket()) {
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("Created storage bucket '{}'", properties.bucket());
        }
    }
}

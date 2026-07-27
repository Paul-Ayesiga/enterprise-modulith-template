package ug.co.smsone.files.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All values are {@code ${S3_*}}-overridable: local SeaweedFS by default, any S3-compatible
 * endpoint (AWS, R2, B2, self-hosted) in other environments.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean bootstrapBucket) {
}

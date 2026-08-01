package ug.co.smsone.files.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * All values are {@code ${S3_*}}-overridable: local SeaweedFS by default, any S3-compatible
 * endpoint (AWS, R2, B2, self-hosted) in other environments.
 */
@ConfigurationProperties(prefix = "app.storage")
record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean bootstrapBucket,
        Duration connectTimeout,
        Duration socketTimeout,
        Duration apiCallTimeout,
        Duration presignTtl,
        DataSize multipartThreshold) {

    StorageProperties {
        // §7: timeouts on anything remote are mandatory — an absent key must not mean "SDK default".
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (socketTimeout == null || socketTimeout.isZero() || socketTimeout.isNegative()) {
            socketTimeout = Duration.ofSeconds(10);
        }
        if (apiCallTimeout == null || apiCallTimeout.isZero() || apiCallTimeout.isNegative()) {
            apiCallTimeout = Duration.ofSeconds(30);
        }
        if (presignTtl == null || presignTtl.isZero() || presignTtl.isNegative()) {
            // long enough to follow the 302 and download; short enough not to be a durable capability
            presignTtl = Duration.ofMinutes(10);
        }
        if (multipartThreshold == null || multipartThreshold.toBytes() <= 0) {
            // S3's minimum part size — below it multipart is pure overhead
            multipartThreshold = DataSize.ofMegabytes(5);
        }
    }
}

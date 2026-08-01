package ug.co.smsone.files.internal;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * SeaweedFS needs {@code forcePathStyle(true)} + explicit endpoint; the same client setup works
 * verbatim against managed S3/R2/B2 by swapping the endpoint/credentials.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class S3ClientConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // §7: explicit timeouts on anything remote. The Apache defaults would let a stalled
                // endpoint hold a request thread ~30s per attempt, and the storage breaker counts
                // only thrown failures — a slow-but-completing call would never open it.
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.socketTimeout()))
                .overrideConfiguration(override -> override.apiCallTimeout(properties.apiCallTimeout()))
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}

package ug.co.smsone.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Boot's default {@code max-file-size} is 1MB, which 413'd every real upload before the handler ran
 * and made the 5MB multipart branch dead code over HTTP — invisible to the suite, because MockMvc
 * bypasses the multipart resolver and the SeaweedFS IT calls {@code putLarge} directly. This pins
 * the deliberate configuration and its relation to the threshold.
 */
class MultipartConfigContractTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private StorageProperties storage;

    @Test
    void uploadLimitClearsTheMultipartThreshold() {
        DataSize maxFile = DataSize.parse(
                environment.getProperty("spring.servlet.multipart.max-file-size", "1MB"));
        DataSize maxRequest = DataSize.parse(
                environment.getProperty("spring.servlet.multipart.max-request-size", "10MB"));

        assertThat(maxFile.toBytes())
                .as("max-file-size must exceed the multipart threshold, or putLarge is unreachable over HTTP")
                .isGreaterThan(storage.multipartThreshold().toBytes());
        assertThat(maxRequest.toBytes()).isGreaterThanOrEqualTo(maxFile.toBytes());
    }
}

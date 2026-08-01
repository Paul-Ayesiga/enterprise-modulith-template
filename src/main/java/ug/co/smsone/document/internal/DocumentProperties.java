package ug.co.smsone.document.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Document policy. */
@ConfigurationProperties(prefix = "app.document")
record DocumentProperties(Duration presignTtl) {

    DocumentProperties {
        if (presignTtl == null || presignTtl.isZero() || presignTtl.isNegative()) {
            // long enough to follow the 302 and download; short enough not to be a durable capability
            presignTtl = Duration.ofMinutes(10);
        }
    }
}

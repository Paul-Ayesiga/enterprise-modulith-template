package ug.co.smsone.gateway.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The usage-report seam target. Unset uri = metering stays edge-local (no ledger, no export). */
@ConfigurationProperties(prefix = "gateway.platform.usage-report")
record EdgeUsageProperties(String uri) {
}

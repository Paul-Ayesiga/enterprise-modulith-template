package ug.co.smsone.billing.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kill Bill connectivity and the plan bridge. {@code planMapping} is OUR plan code → Kill Bill
 * catalog plan name (FREE is deliberately absent: no money, no Kill Bill subscription);
 * {@code planPrices} feeds the dev bootstrap's simple-plan creation only — production catalogs
 * are uploaded to Kill Bill, not defined here.
 */
@ConfigurationProperties(prefix = "app.billing")
record KillBillProperties(
        String baseUrl,
        String username,
        String password,
        String apiKey,
        String apiSecret,
        String createdBy,
        String callbackToken,
        String callbackUrl,
        Boolean bootstrap,
        String currency,
        Duration connectTimeout,
        Duration readTimeout,
        Map<String, String> planMapping,
        Map<String, BigDecimal> planPrices) {

    KillBillProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8082";
        }
        if (username == null || username.isBlank()) {
            username = "admin";
        }
        if (password == null || password.isBlank()) {
            password = "password";
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "smsone";
        }
        if (apiSecret == null || apiSecret.isBlank()) {
            apiSecret = "smsone-secret";
        }
        if (createdBy == null || createdBy.isBlank()) {
            createdBy = "smsone-platform";
        }
        if (callbackToken == null || callbackToken.isBlank()) {
            throw new IllegalStateException(
                    "app.billing.callback-token must not be blank (set BILLING_CALLBACK_TOKEN)");
        }
        if (callbackUrl == null) {
            callbackUrl = "";
        }
        if (bootstrap == null) {
            bootstrap = Boolean.FALSE; // dev opt-in, like the org dev-bootstrap
        }
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = Duration.ofSeconds(15);
        }
        if (planMapping == null || planMapping.isEmpty()) {
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("PRO", "pro-monthly");
            defaults.put("ENTERPRISE", "enterprise-monthly");
            planMapping = defaults;
        }
        if (planPrices == null || planPrices.isEmpty()) {
            Map<String, BigDecimal> defaults = new LinkedHashMap<>();
            defaults.put("PRO", new BigDecimal("49.00"));
            defaults.put("ENTERPRISE", new BigDecimal("499.00"));
            planPrices = defaults;
        }
    }

    /** Reverse bridge: a Kill Bill plan name back to OUR code; null when unmapped. */
    String planCodeFor(String killBillPlanName) {
        return planMapping.entrySet().stream()
                .filter(entry -> entry.getValue().equals(killBillPlanName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}

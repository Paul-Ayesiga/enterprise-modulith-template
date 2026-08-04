package ug.co.smsone.payments.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static (env-driven) gateway config — the fallback when the integration hub has no
 * {@code PAYMENT_GATEWAY} entry. {@code mode} switches sandbox ↔ live per gateway; both base URLs
 * are pinned to the vendors' published endpoints and overridable. The Pesapal sandbox defaults are
 * Pesapal's own PUBLISHED Ugandan demo merchant keys (developer.pesapal.com/api3-demo-keys.txt) —
 * real sandbox calls work out of the box; live always needs your merchant credentials.
 */
@ConfigurationProperties(prefix = "app.payments")
record PaymentsProperties(Pesapal pesapal, Yo yo, Tax tax) {

    static final String SANDBOX = "sandbox";
    static final String LIVE = "live";

    PaymentsProperties {
        if (pesapal == null) {
            pesapal = new Pesapal(null, null, null, null, null, null, null, null);
        }
        if (yo == null) {
            yo = new Yo(null, null, null, null, null);
        }
        if (tax == null) {
            tax = new Tax(null);
        }
    }

    /** VAT on collections, prices treated as inclusive. Rate 0 (default) = no tax lines at all. */
    record Tax(java.math.BigDecimal ratePercent) {
        Tax {
            if (ratePercent == null || ratePercent.signum() < 0) {
                ratePercent = java.math.BigDecimal.ZERO;
            }
        }

        boolean enabled() {
            return ratePercent.signum() > 0;
        }
    }

    record Pesapal(String mode, String consumerKey, String consumerSecret, String sandboxBaseUrl,
            String liveBaseUrl, String callbackUrl, String ipnUrl, String ipnId) {

        Pesapal {
            mode = normalizeMode(mode);
            if (sandboxBaseUrl == null || sandboxBaseUrl.isBlank()) {
                sandboxBaseUrl = "https://cybqa.pesapal.com/pesapalv3";
            }
            if (liveBaseUrl == null || liveBaseUrl.isBlank()) {
                liveBaseUrl = "https://pay.pesapal.com/v3";
            }
            if (consumerKey == null || consumerKey.isBlank()) {
                consumerKey = "TDpigBOOhs+zAl8cwH2Fl82jJGyD8xev"; // published UG demo key (sandbox only)
            }
            if (consumerSecret == null || consumerSecret.isBlank()) {
                consumerSecret = "1KpqkfsMaihIcOlhnBo/gBZ5smw="; // published UG demo secret (sandbox only)
            }
            if (callbackUrl == null || callbackUrl.isBlank()) {
                callbackUrl = "http://localhost:28080/api/v1/payments/pesapal/callback";
            }
            if (ipnUrl == null || ipnUrl.isBlank()) {
                ipnUrl = "http://localhost:28080/api/v1/payments/pesapal/ipn";
            }
        }

        String baseUrl() {
            return LIVE.equals(mode) ? liveBaseUrl : sandboxBaseUrl;
        }
    }

    record Yo(String mode, String apiUsername, String apiPassword, String sandboxBaseUrl, String liveBaseUrl) {

        Yo {
            mode = normalizeMode(mode);
            if (sandboxBaseUrl == null || sandboxBaseUrl.isBlank()) {
                sandboxBaseUrl = "https://sandbox.yo.co.ug/services/yopaymentsdev/task.php";
            }
            if (liveBaseUrl == null || liveBaseUrl.isBlank()) {
                liveBaseUrl = "https://paymentsapi1.yo.co.ug/ybs/task.php";
            }
        }

        String baseUrl() {
            return LIVE.equals(mode) ? liveBaseUrl : sandboxBaseUrl;
        }

        boolean configured() {
            return apiUsername != null && !apiUsername.isBlank()
                    && apiPassword != null && !apiPassword.isBlank();
        }
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SANDBOX; // safe default: nothing charges real money until LIVE is explicit
        }
        String normalized = mode.trim().toLowerCase();
        if (!SANDBOX.equals(normalized) && !LIVE.equals(normalized)) {
            throw new IllegalStateException("payments mode must be 'sandbox' or 'live', got: " + mode);
        }
        return normalized;
    }
}

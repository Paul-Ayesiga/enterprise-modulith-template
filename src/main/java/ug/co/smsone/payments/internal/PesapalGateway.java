package ug.co.smsone.payments.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.integration.Integrations;

/**
 * Pesapal API 3.0 (JSON). The flow, per developer.pesapal.com: authenticate
 * ({@code POST /api/Auth/RequestToken}, bearer token valid 5 minutes — cached and refreshed with a
 * 30s safety margin), ensure an IPN registration ({@code POST /api/URLSetup/RegisterIPN} — lazy,
 * cached per credential set, skipped when an {@code ipnId} is configured), submit the order
 * ({@code POST /api/Transactions/SubmitOrderRequest}) and send the customer to the returned
 * {@code redirect_url} (hosted page: card + mobile money), then confirm outcomes ONLY via
 * {@code GET /api/Transactions/GetTransactionStatus} — the IPN ping and the browser callback are
 * triggers, never truth. Status mapping: status_code 1=COMPLETED, 2=FAILED, 3=REVERSED, 0=INVALID —
 * with description PENDING while the hosted page is unpaid.
 */
@Component
class PesapalGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PesapalGateway.class);
    static final String PROVIDER = "pesapal";

    private final RestClient restClient;
    private final PaymentsProperties properties;
    private final ObjectProvider<Integrations> integrations;
    private final ConcurrentMap<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> ipnIds = new ConcurrentHashMap<>();

    PesapalGateway(RestClient paymentsRestClient, PaymentsProperties properties,
            ObjectProvider<Integrations> integrations) {
        this.restClient = paymentsRestClient;
        this.properties = properties;
        this.integrations = integrations;
    }

    private record CachedToken(String token, Instant refreshAfter) {
    }

    private record Config(String mode, String baseUrl, String consumerKey, String consumerSecret,
            String callbackUrl, String ipnUrl, String ipnId) {
    }

    record TokenResponse(String token, String expiryDate, String status, String message) {
    }

    record IpnResponse(String ipn_id, String url) {
    }

    record OrderResponse(String order_tracking_id, String merchant_reference, String redirect_url,
            String status, Object error) {
    }

    record StatusResponse(String payment_method, Object amount, String confirmation_code,
            String payment_status_description, String description, Integer status_code,
            String merchant_reference, String currency, String message) {
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String mode(UUID orgId) {
        return config(orgId).mode();
    }

    @Override
    public Initiation initiate(UUID orgId, Payment payment) {
        Config config = config(orgId);
        String token = token(config);
        String notificationId = ipnId(config, token);

        Map<String, Object> billing = new LinkedHashMap<>();
        if (payment.getEmail() != null) {
            billing.put("email_address", payment.getEmail());
        }
        if (payment.getPhoneNumber() != null) {
            billing.put("phone_number", payment.getPhoneNumber());
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", payment.getMerchantReference());
        order.put("currency", payment.getCurrency());
        order.put("amount", payment.getAmount());
        order.put("description", payment.getDescription());
        order.put("callback_url", config.callbackUrl());
        order.put("notification_id", notificationId);
        order.put("billing_address", billing);

        OrderResponse response = restClient.post()
                .uri(config.baseUrl() + "/api/Transactions/SubmitOrderRequest")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(order)
                .retrieve()
                .body(OrderResponse.class);
        if (response == null || response.order_tracking_id() == null) {
            throw new IllegalStateException("Pesapal did not accept the order: "
                    + (response == null ? "empty response" : String.valueOf(response.error())));
        }
        log.info("Pesapal order submitted: org={} ref={} trackingId={} mode={}",
                orgId, payment.getMerchantReference(), response.order_tracking_id(), config.mode());
        return new Initiation(response.order_tracking_id(), response.redirect_url(),
                PaymentStatus.PENDING, "Awaiting payment on the Pesapal hosted page");
    }

    @Override
    public StatusResult status(UUID orgId, Payment payment) {
        Config config = config(orgId);
        StatusResponse response = restClient.get()
                .uri(config.baseUrl() + "/api/Transactions/GetTransactionStatus?orderTrackingId={id}",
                        payment.getGatewayReference())
                .header("Authorization", "Bearer " + token(config))
                .retrieve()
                .body(StatusResponse.class);
        if (response == null) {
            throw new IllegalStateException("Pesapal returned an empty status");
        }
        return new StatusResult(map(response), describe(response), response.confirmation_code());
    }

    /** status_code is authoritative (1/2/3); an unpaid order reads PENDING from the description. */
    private static PaymentStatus map(StatusResponse response) {
        Integer code = response.status_code();
        if (code != null) {
            switch (code) {
                case 1: return PaymentStatus.COMPLETED;
                case 2: return PaymentStatus.FAILED;
                case 3: return PaymentStatus.REVERSED;
                default: // 0 — fall through to the description
            }
        }
        String description = response.payment_status_description();
        if (description != null && description.equalsIgnoreCase("PENDING")) {
            return PaymentStatus.PENDING;
        }
        return PaymentStatus.INVALID;
    }

    private static String describe(StatusResponse response) {
        String description = response.payment_status_description();
        String method = response.payment_method();
        return method == null ? description : description + " via " + method;
    }

    private String token(Config config) {
        String cacheKey = config.baseUrl() + "|" + config.consumerKey();
        CachedToken cached = tokens.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.refreshAfter())) {
            return cached.token();
        }
        TokenResponse response = restClient.post()
                .uri(config.baseUrl() + "/api/Auth/RequestToken")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("consumer_key", config.consumerKey(), "consumer_secret", config.consumerSecret()))
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.token() == null) {
            throw new IllegalStateException("Pesapal authentication failed: "
                    + (response == null ? "empty response" : response.message()));
        }
        // Tokens live 5 minutes; refresh at 4m30s so an in-flight call never rides an expired one.
        tokens.put(cacheKey, new CachedToken(response.token(),
                Instant.now().plus(Duration.ofMinutes(4).plusSeconds(30))));
        return response.token();
    }

    /** Lazy one-time IPN registration per credential set; a configured ipnId skips the round-trip. */
    private String ipnId(Config config, String token) {
        if (config.ipnId() != null && !config.ipnId().isBlank()) {
            return config.ipnId();
        }
        String cacheKey = config.baseUrl() + "|" + config.consumerKey();
        return ipnIds.computeIfAbsent(cacheKey, key -> {
            IpnResponse response = restClient.post()
                    .uri(config.baseUrl() + "/api/URLSetup/RegisterIPN")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", config.ipnUrl(), "ipn_notification_type", "GET"))
                    .retrieve()
                    .body(IpnResponse.class);
            if (response == null || response.ipn_id() == null) {
                throw new IllegalStateException("Pesapal IPN registration failed for " + config.ipnUrl());
            }
            log.info("Pesapal IPN registered: {} -> {}", response.url(), response.ipn_id());
            return response.ipn_id();
        });
    }

    /** Integration hub (provider {@code pesapal}, org override wins) first; env config as fallback. */
    private Config config(UUID orgId) {
        PaymentsProperties.Pesapal env = properties.pesapal();
        Integrations hub = integrations.getIfAvailable();
        if (hub != null) {
            var resolved = hub.resolve(orgId, Integrations.Kind.PAYMENT_GATEWAY)
                    .filter(integration -> PROVIDER.equalsIgnoreCase(integration.provider()))
                    .orElse(null);
            if (resolved != null) {
                Map<String, String> settings = resolved.settings();
                String mode = settings.getOrDefault("mode", env.mode());
                String baseUrl = PaymentsProperties.LIVE.equals(mode) ? env.liveBaseUrl() : env.sandboxBaseUrl();
                return new Config(mode,
                        settings.getOrDefault("baseUrl", baseUrl),
                        settings.getOrDefault("consumerKey", env.consumerKey()),
                        settings.getOrDefault("consumerSecret", env.consumerSecret()),
                        settings.getOrDefault("callbackUrl", env.callbackUrl()),
                        settings.getOrDefault("ipnUrl", env.ipnUrl()),
                        settings.getOrDefault("ipnId", env.ipnId()));
            }
        }
        return new Config(env.mode(), env.baseUrl(), env.consumerKey(), env.consumerSecret(),
                env.callbackUrl(), env.ipnUrl(), env.ipnId());
    }
}

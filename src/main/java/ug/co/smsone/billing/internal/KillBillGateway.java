package ug.co.smsone.billing.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The Kill Bill client — all remote, all timeout-bounded, never inside a local transaction
 * (callers observe §4's rule). Kill Bill's write idiom is 201 + a {@code Location} whose last
 * segment is the created id; reads are plain JSON. Accounts are keyed both ways: our org id is
 * the KB {@code externalKey}, the KB {@code accountId} lands in {@code billing_account}.
 */
@Component
class KillBillGateway {

    private static final Logger log = LoggerFactory.getLogger(KillBillGateway.class);

    private final RestClient killBill;
    private final KillBillProperties properties;

    KillBillGateway(RestClient killBillRestClient, KillBillProperties properties) {
        this.killBill = killBillRestClient;
        this.properties = properties;
    }

    record KbSubscription(String planName, String state) {
    }

    record KbInvoice(String invoiceId, String invoiceNumber, BigDecimal amount, BigDecimal balance,
            String currency, String invoiceDate, String status) {
    }

    record KbAccountView(UUID accountId, BigDecimal balance, String currency) {
    }

    record KbPaymentMethod(UUID paymentMethodId, String pluginName, boolean isDefault) {
    }

    /** Idempotent: an existing externalKey resolves to the existing account, never a duplicate. */
    UUID ensureAccount(UUID orgId, String name) {
        Optional<UUID> existing = findAccountByExternalKey(orgId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            ResponseEntity<Void> response = killBill.post()
                    .uri("/1.0/kb/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("externalKey", orgId.toString(), "name", name,
                            "currency", properties.currency()))
                    .retrieve()
                    .toBodilessEntity();
            return idFromLocation(response.getHeaders().getLocation());
        } catch (HttpClientErrorException.Conflict conflict) {
            // Lost a create race: the winner's account is the account.
            return findAccountByExternalKey(orgId).orElseThrow(() -> conflict);
        }
    }

    Optional<UUID> findAccountByExternalKey(UUID orgId) {
        try {
            Map<?, ?> account = killBill.get()
                    .uri(uri -> uri.path("/1.0/kb/accounts").queryParam("externalKey", orgId.toString()).build())
                    .retrieve()
                    .body(Map.class);
            return account == null ? Optional.empty()
                    : Optional.of(UUID.fromString(String.valueOf(account.get("accountId"))));
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    KbAccountView accountView(UUID kbAccountId) {
        Map<?, ?> account = killBill.get()
                .uri(uri -> uri.path("/1.0/kb/accounts/" + kbAccountId)
                        .queryParam("accountWithBalance", true).build())
                .retrieve()
                .body(Map.class);
        BigDecimal balance = account != null && account.get("accountBalance") != null
                ? new BigDecimal(String.valueOf(account.get("accountBalance")))
                : BigDecimal.ZERO;
        return new KbAccountView(kbAccountId, balance,
                account == null ? properties.currency() : String.valueOf(account.get("currency")));
    }

    UUID createSubscription(UUID kbAccountId, String planName) {
        ResponseEntity<Void> response = killBill.post()
                .uri("/1.0/kb/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountId", kbAccountId.toString(), "planName", planName))
                .retrieve()
                .toBodilessEntity();
        return idFromLocation(response.getHeaders().getLocation());
    }

    List<KbSubscription> subscriptions(UUID kbAccountId) {
        List<?> bundles = killBill.get()
                .uri("/1.0/kb/accounts/" + kbAccountId + "/bundles")
                .retrieve()
                .body(List.class);
        List<KbSubscription> result = new ArrayList<>();
        if (bundles == null) {
            return result;
        }
        for (Object bundle : bundles) {
            if (bundle instanceof Map<?, ?> map && map.get("subscriptions") instanceof List<?> subs) {
                for (Object sub : subs) {
                    if (sub instanceof Map<?, ?> s) {
                        result.add(new KbSubscription(
                                String.valueOf(s.get("planName")), String.valueOf(s.get("state"))));
                    }
                }
            }
        }
        return result;
    }

    /** Human summary line per payment method, for the billing overview. Structured form below. */
    List<String> paymentMethods(UUID kbAccountId) {
        List<?> methods = killBill.get()
                .uri("/1.0/kb/accounts/" + kbAccountId + "/paymentMethods")
                .retrieve()
                .body(List.class);
        List<String> result = new ArrayList<>();
        if (methods == null) {
            return result;
        }
        for (Object method : methods) {
            if (method instanceof Map<?, ?> m) {
                result.add(String.valueOf(m.get("pluginName"))
                        + (Boolean.TRUE.equals(m.get("isDefault")) ? " (default)" : ""));
            }
        }
        return result;
    }

    /** Structured payment methods for the management surface (id + plugin + default flag). */
    List<KbPaymentMethod> paymentMethodDetails(UUID kbAccountId) {
        List<?> methods = killBill.get()
                .uri("/1.0/kb/accounts/" + kbAccountId + "/paymentMethods")
                .retrieve()
                .body(List.class);
        List<KbPaymentMethod> result = new ArrayList<>();
        if (methods == null) {
            return result;
        }
        for (Object method : methods) {
            if (method instanceof Map<?, ?> m && m.get("paymentMethodId") != null) {
                result.add(new KbPaymentMethod(
                        UUID.fromString(String.valueOf(m.get("paymentMethodId"))),
                        String.valueOf(m.get("pluginName")),
                        Boolean.TRUE.equals(m.get("isDefault"))));
            }
        }
        return result;
    }

    /**
     * Attach a payment method to the account through a Kill Bill payment PLUGIN. Raw card data never
     * reaches us: {@code pluginProperties} carries only what a hosted page / plugin tokenized (a
     * plugin reference, a nonce), which keeps this out of PCI scope — the card-capture UI is the
     * plugin's job. Returns the created payment method id (KB's 201 + Location idiom).
     */
    UUID addPaymentMethod(UUID kbAccountId, String pluginName, boolean isDefault,
            Map<String, Object> pluginProperties) {
        ResponseEntity<Void> response = killBill.post()
                .uri(uri -> uri.path("/1.0/kb/accounts/" + kbAccountId + "/paymentMethods")
                        .queryParam("isDefault", isDefault).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pluginName", pluginName,
                        "pluginInfo", pluginProperties == null ? Map.of() : pluginProperties))
                .retrieve()
                .toBodilessEntity();
        return idFromLocation(response.getHeaders().getLocation());
    }

    /** Make one of the account's payment methods the one Kill Bill charges. */
    void setDefaultPaymentMethod(UUID kbAccountId, UUID paymentMethodId) {
        killBill.put()
                .uri("/1.0/kb/accounts/" + kbAccountId + "/paymentMethods/" + paymentMethodId + "/setDefault")
                .retrieve()
                .toBodilessEntity();
    }

    /** Detach a payment method (Kill Bill refuses to remove one while it is the account default). */
    void deletePaymentMethod(UUID kbAccountId, UUID paymentMethodId) {
        killBill.delete()
                .uri("/1.0/kb/accounts/" + kbAccountId + "/paymentMethods/" + paymentMethodId)
                .retrieve()
                .toBodilessEntity();
    }

    List<KbInvoice> invoices(UUID kbAccountId) {
        List<?> invoices = killBill.get()
                .uri("/1.0/kb/accounts/" + kbAccountId + "/invoices")
                .retrieve()
                .body(List.class);
        List<KbInvoice> result = new ArrayList<>();
        if (invoices == null) {
            return result;
        }
        for (Object invoice : invoices) {
            if (invoice instanceof Map<?, ?> i) {
                result.add(new KbInvoice(
                        String.valueOf(i.get("invoiceId")),
                        String.valueOf(i.get("invoiceNumber")),
                        decimal(i.get("amount")),
                        decimal(i.get("balance")),
                        String.valueOf(i.get("currency")),
                        String.valueOf(i.get("invoiceDate")),
                        String.valueOf(i.get("status"))));
            }
        }
        return result;
    }

    /** Dev bootstrap only: tenant + simple catalog plans + the push-notification callback. */
    void ensureTenant() {
        try {
            killBill.post()
                    .uri("/1.0/kb/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("apiKey", properties.apiKey(), "apiSecret", properties.apiSecret()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Kill Bill tenant '{}' created", properties.apiKey());
        } catch (HttpClientErrorException conflict) {
            if (conflict.getStatusCode() != HttpStatusCode.valueOf(409)) {
                throw conflict;
            }
        }
    }

    void ensureSimplePlan(String planName, String productName, BigDecimal amount) {
        try {
            killBill.post()
                    .uri("/1.0/kb/catalog/simplePlan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "planId", planName,
                            "productName", productName,
                            "productCategory", "BASE",
                            "currency", properties.currency(),
                            "amount", amount,
                            "billingPeriod", "MONTHLY",
                            "trialLength", 0,
                            "trialTimeUnit", "DAYS"))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Kill Bill simple plan '{}' ensured", planName);
        } catch (HttpClientErrorException conflict) {
            // An existing plan answers 4xx depending on version — existing is exactly what we want.
            log.debug("Kill Bill simplePlan '{}' not (re)created: {}", planName, conflict.getStatusCode());
        }
    }

    void registerNotificationCallback(String callbackUrl) {
        killBill.post()
                .uri(uri -> uri.path("/1.0/kb/tenants/registerNotificationCallback")
                        .queryParam("cb", callbackUrl).build())
                .retrieve()
                .toBodilessEntity();
        log.info("Kill Bill push notifications registered to {}", callbackUrl);
    }

    private static UUID idFromLocation(URI location) {
        if (location == null) {
            throw new IllegalStateException("Kill Bill answered 201 without a Location header");
        }
        String path = location.getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}

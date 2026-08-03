package ug.co.smsone.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ug.co.smsone.integration.Integrations;

/**
 * The Pesapal API 3.0 wire (developer.pesapal.com) without Pesapal: authenticate → register IPN
 * (lazily, once) → SubmitOrderRequest with the spec's exact field names → redirect_url back;
 * GetTransactionStatus maps status_code 1 to COMPLETED; the 5-minute token is fetched once and
 * reused across calls.
 */
class PesapalGatewayTest {

    private static final String BASE = "https://pesapal.test";

    private final PaymentsProperties properties = new PaymentsProperties(
            new PaymentsProperties.Pesapal("sandbox", "ck", "cs", BASE, "https://live.invalid",
                    "https://app.test/callback", "https://app.test/ipn", null),
            null);

    private MockRestServiceServer server;
    private PesapalGateway gateway;

    private void build() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PesapalGateway(builder.build(), properties, absentHub());
    }

    @Test
    void authenticatesRegistersIpnOnceAndSubmitsTheOrder() {
        build();
        server.expect(once(), requestTo(BASE + "/api/Auth/RequestToken"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.consumer_key").value("ck"))
                .andExpect(jsonPath("$.consumer_secret").value("cs"))
                .andRespond(withSuccess("{\"token\":\"jwt-1\",\"expiryDate\":\"x\",\"status\":\"200\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/api/URLSetup/RegisterIPN"))
                .andExpect(header("Authorization", "Bearer jwt-1"))
                .andExpect(jsonPath("$.url").value("https://app.test/ipn"))
                .andExpect(jsonPath("$.ipn_notification_type").value("GET"))
                .andRespond(withSuccess("{\"ipn_id\":\"ipn-77\",\"url\":\"https://app.test/ipn\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/api/Transactions/SubmitOrderRequest"))
                .andExpect(header("Authorization", "Bearer jwt-1")) // token reused — no second auth
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.currency").value("UGX"))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.callback_url").value("https://app.test/callback"))
                .andExpect(jsonPath("$.notification_id").value("ipn-77"))
                .andExpect(jsonPath("$.billing_address.email_address").value("payer@acme.test"))
                .andRespond(withSuccess("{\"order_tracking_id\":\"track-9\",\"merchant_reference\":\"m\","
                        + "\"redirect_url\":\"https://cybqa.pesapal.com/pay/iframe?x=1\",\"status\":\"200\"}",
                        MediaType.APPLICATION_JSON));

        Payment payment = Payment.initiate(UUID.randomUUID(), "pesapal", "sandbox",
                new BigDecimal("15000"), "UGX", "PRO invoice", null, "payer@acme.test", Instant.now());
        PaymentGateway.Initiation initiation = gateway.initiate(null, payment);

        assertThat(initiation.gatewayReference()).isEqualTo("track-9");
        assertThat(initiation.redirectUrl()).contains("cybqa.pesapal.com");
        assertThat(initiation.status()).isEqualTo(PaymentStatus.PENDING);
        server.verify();
    }

    @Test
    void statusMapsTheSpecCodes() {
        build();
        server.expect(once(), requestTo(BASE + "/api/Auth/RequestToken"))
                .andRespond(withSuccess("{\"token\":\"jwt-2\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/api/Transactions/GetTransactionStatus?orderTrackingId=track-9"))
                .andExpect(header("Authorization", "Bearer jwt-2"))
                .andRespond(withSuccess("{\"payment_method\":\"MpesaUG\",\"status_code\":1,"
                        + "\"payment_status_description\":\"Completed\",\"confirmation_code\":\"CONF-1\"}",
                        MediaType.APPLICATION_JSON));

        Payment payment = Payment.initiate(UUID.randomUUID(), "pesapal", "sandbox",
                new BigDecimal("15000"), "UGX", "PRO invoice", null, "payer@acme.test", Instant.now());
        payment.initiated("track-9", "https://r", PaymentStatus.PENDING, "d", Instant.now());

        PaymentGateway.StatusResult result = gateway.status(null, payment);
        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.confirmationCode()).isEqualTo("CONF-1");
        server.verify();
    }

    private static ObjectProvider<Integrations> absentHub() {
        return new ObjectProvider<>() {
            @Override
            public Integrations getObject() {
                throw new IllegalStateException("absent");
            }

            @Override
            public Integrations getIfAvailable() {
                return null;
            }
        };
    }
}

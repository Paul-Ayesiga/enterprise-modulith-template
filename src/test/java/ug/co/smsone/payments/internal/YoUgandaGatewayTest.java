package ug.co.smsone.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
 * The Yo! Payments XML wire (the vendor's AutoCreate contract) without Yo!: acdepositfunds with
 * NonBlocking TRUE and a digits-only Account, PENDING + TransactionReference back;
 * actransactioncheckstatus mapping SUCCEEDED → COMPLETED; a Status != OK response throws with the
 * vendor's message.
 */
class YoUgandaGatewayTest {

    private static final String BASE = "https://yo.test/task.php";

    private final PaymentsProperties properties = new PaymentsProperties(null,
            new PaymentsProperties.Yo("sandbox", "yo-user", "yo-pass", BASE, "https://live.invalid"));

    private MockRestServiceServer server;
    private YoUgandaGateway gateway;

    private void build() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new YoUgandaGateway(builder.build(), properties, absentHub());
    }

    private static Payment payment(String phone) {
        return Payment.initiate(UUID.randomUUID(), "yo-uganda", "sandbox",
                new BigDecimal("5000"), "UGX", "Airtime topup", phone, null, Instant.now());
    }

    @Test
    void depositsWithTheAutoCreateXmlAndReadsThePendingReference() {
        build();
        server.expect(requestTo(BASE))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("<APIUsername>yo-user</APIUsername>"),
                        org.hamcrest.Matchers.containsString("<APIPassword>yo-pass</APIPassword>"),
                        org.hamcrest.Matchers.containsString("<Method>acdepositfunds</Method>"),
                        org.hamcrest.Matchers.containsString("<NonBlocking>TRUE</NonBlocking>"),
                        org.hamcrest.Matchers.containsString("<Amount>5000</Amount>"),
                        org.hamcrest.Matchers.containsString("<Account>256772123456</Account>"),
                        org.hamcrest.Matchers.containsString("<Narrative>Airtime topup</Narrative>"))))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <AutoCreate><Response>
                          <Status>OK</Status><StatusCode>0</StatusCode>
                          <TransactionStatus>PENDING</TransactionStatus>
                          <TransactionReference>yo-ref-42</TransactionReference>
                        </Response></AutoCreate>""", MediaType.TEXT_XML));

        // Formatting and the + are stripped: the Account element carries bare digits.
        PaymentGateway.Initiation initiation = gateway.initiate(null, payment("+256 772-123456"));

        assertThat(initiation.gatewayReference()).isEqualTo("yo-ref-42");
        assertThat(initiation.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(initiation.redirectUrl()).isNull(); // handset prompt — nothing to redirect to
        server.verify();
    }

    @Test
    void statusCheckMapsSucceededToCompleted() {
        build();
        server.expect(requestTo(BASE))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("<Method>actransactioncheckstatus</Method>"),
                        org.hamcrest.Matchers.containsString("<TransactionReference>yo-ref-42</TransactionReference>"))))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <AutoCreate><Response>
                          <Status>OK</Status><StatusCode>0</StatusCode>
                          <TransactionStatus>SUCCEEDED</TransactionStatus>
                          <MNOTransactionReferenceId>MTN-778899</MNOTransactionReferenceId>
                        </Response></AutoCreate>""", MediaType.TEXT_XML));

        Payment paid = payment("256772123456");
        paid.initiated("yo-ref-42", null, PaymentStatus.PENDING, "d", Instant.now());

        PaymentGateway.StatusResult result = gateway.status(null, paid);
        assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.confirmationCode()).isEqualTo("MTN-778899");
        server.verify();
    }

    @Test
    void aVendorErrorThrowsWithItsMessage() {
        build();
        server.expect(requestTo(BASE))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <AutoCreate><Response>
                          <Status>ERROR</Status><StatusCode>-21</StatusCode>
                          <StatusMessage>Authentication failed</StatusMessage>
                        </Response></AutoCreate>""", MediaType.TEXT_XML));

        assertThatThrownBy(() -> gateway.initiate(null, payment("256772123456")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authentication failed");
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

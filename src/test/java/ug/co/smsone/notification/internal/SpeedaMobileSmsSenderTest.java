package ug.co.smsone.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ug.co.smsone.notification.NotificationMessage;

/**
 * The Speeda Mobile wire contract (Web SMS API v1.13) without the real gateway: the POST body carries
 * the spec's exact field names, the phone number is normalized to digits (no {@code +}), unicode text
 * auto-upgrades the encoding, an S response succeeds and an F response throws (so the dispatcher
 * records + retries), and hub settings beat the env fallback key-by-key.
 */
class SpeedaMobileSmsSenderTest {

    private final SpeedaSmsProperties env = new SpeedaSmsProperties(
            "http://speeda.test", "API-ENV", "env-secret", "SpeedaFin", null, null);

    private MockRestServiceServer server;

    private SpeedaMobileSmsSender sender() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new SpeedaMobileSmsSender(builder.build(), env);
    }

    @Test
    void postsTheSpecBodyAndAcceptsAnSResponse() {
        SpeedaMobileSmsSender sms = sender();
        server.expect(requestTo("http://speeda.test/api/SendSMS"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.api_id").value("API-ENV"))
                .andExpect(jsonPath("$.api_password").value("env-secret"))
                .andExpect(jsonPath("$.sms_type").value("T"))
                .andExpect(jsonPath("$.encoding").value("T"))
                .andExpect(jsonPath("$.sender_id").value("SpeedaFin"))
                .andExpect(jsonPath("$.phonenumber").value("256772123456"))
                .andExpect(jsonPath("$.textmessage").value("Your code is 4711"))
                .andRespond(withSuccess(
                        "{\"message_id\":4125,\"status\":\"S\",\"remarks\":\"Message Submitted Successfully\"}",
                        MediaType.APPLICATION_JSON));

        // The + and formatting are the caller's habit; the wire wants bare digits.
        sms.send(new NotificationMessage("+256 772-123456", "code", "Your code is 4711", Map.of()), Map.of());
        server.verify();
    }

    @Test
    void anFResponseThrowsSoTheDispatcherRecordsTheFailure() {
        SpeedaMobileSmsSender sms = sender();
        server.expect(requestTo("http://speeda.test/api/SendSMS"))
                .andRespond(withSuccess("{\"message_id\":0,\"status\":\"F\",\"remarks\":\"Invalid Sender\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sms.send(new NotificationMessage("256772123456", "s", "b", Map.of()), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid Sender");
    }

    @Test
    void unicodeTextUpgradesTheEncoding() {
        SpeedaMobileSmsSender sms = sender();
        server.expect(requestTo("http://speeda.test/api/SendSMS"))
                .andExpect(jsonPath("$.encoding").value("U"))
                .andRespond(withSuccess("{\"message_id\":1,\"status\":\"S\",\"remarks\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        sms.send(new NotificationMessage("256772123456", "s", "Habari — karibu 😊", Map.of()), Map.of());
        server.verify();
    }

    @Test
    void hubSettingsBeatTheEnvFallbackKeyByKey() {
        SpeedaMobileSmsSender sms = sender();
        server.expect(requestTo("http://speeda.test/api/SendSMS"))
                .andExpect(jsonPath("$.api_id").value("API-HUB"))
                .andExpect(jsonPath("$.sender_id").value("HubSender"))
                .andExpect(jsonPath("$.api_password").value("env-secret")) // missing key -> env fills it
                .andRespond(withSuccess("{\"message_id\":2,\"status\":\"S\",\"remarks\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        sms.send(new NotificationMessage("256772123456", "s", "b", Map.of()),
                Map.of("apiId", "API-HUB", "senderId", "HubSender"));
        server.verify();
    }

    @Test
    void phoneNormalizationAndEncodingRules() {
        assertThat(SpeedaMobileSmsSender.normalizePhone("+256 (772) 123-456")).isEqualTo("256772123456");
        assertThat(SpeedaMobileSmsSender.normalizePhone("256772123456")).isEqualTo("256772123456");
        assertThatThrownBy(() -> SpeedaMobileSmsSender.normalizePhone("not-a-phone"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(SpeedaMobileSmsSender.encodingFor("plain ascii", "T")).isEqualTo("T");
        assertThat(SpeedaMobileSmsSender.encodingFor("émoji ✨", "T")).isEqualTo("U");
        assertThat(SpeedaMobileSmsSender.encodingFor("émoji ✨", "FS")).isEqualTo("FS");
    }

}

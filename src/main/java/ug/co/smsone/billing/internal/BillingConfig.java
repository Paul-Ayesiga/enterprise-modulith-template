package ug.co.smsone.billing.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KillBillProperties.class)
class BillingConfig {

    /**
     * The Kill Bill client: basic auth + the tenant key pair on every call, and REAL timeouts —
     * a stalled billing host must never pin a request thread (§7's rule, the Keycloak client's
     * precedent). {@code X-Killbill-CreatedBy} is Kill Bill's mandatory write-attribution header.
     */
    @Bean
    RestClient killBillRestClient(KillBillProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());
        String basic = java.util.Base64.getEncoder().encodeToString(
                (properties.username() + ":" + properties.password())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("X-Killbill-ApiKey", properties.apiKey())
                .defaultHeader("X-Killbill-ApiSecret", properties.apiSecret())
                .defaultHeader("X-Killbill-CreatedBy", properties.createdBy())
                .build();
    }
}

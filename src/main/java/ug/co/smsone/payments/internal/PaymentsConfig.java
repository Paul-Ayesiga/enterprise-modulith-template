package ug.co.smsone.payments.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsProperties.class)
class PaymentsConfig {

    /** One HTTP client for both gateways, with real timeouts — a stalled PSP must not pin a request thread. */
    @Bean
    RestClient paymentsRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000); // PSP order submits can be slow; still bounded
        return RestClient.builder().requestFactory(factory).build();
    }
}

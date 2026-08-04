package ug.co.smsone.notification.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationProperties.class, SpeedaSmsProperties.class})
class NotificationConfig {

    /**
     * The Speeda Mobile HTTP client, with real timeouts — a stalled SMS gateway must not pin a
     * delivery-worker permit (the SMTP client next door follows the same rule).
     */
    @Bean
    RestClient speedaRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder().requestFactory(factory).build();
    }
}

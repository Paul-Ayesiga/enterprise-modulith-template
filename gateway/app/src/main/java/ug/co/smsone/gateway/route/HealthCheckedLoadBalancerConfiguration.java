package ug.co.smsone.gateway.route;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The per-load-balancer-client configuration referenced by {@link GatewayLoadBalancerConfig}. It is a
 * PLAIN class — not {@code @Configuration}, not component-scanned — so Spring Cloud LoadBalancer
 * instantiates it once per client child context. That matters: {@code build(context)} reads the
 * {@code serviceId} from the client context (e.g. {@code modulith}), and a health-checked discovery
 * supplier requires it. (Registered from the main context, the serviceId is empty and construction fails.)
 *
 * <p>The health-check supplier probes each instance over HTTP, so it needs a {@link WebClient.Builder};
 * the LB client child context doesn't inherit the main context's, so one is declared here.
 */
class HealthCheckedLoadBalancerConfiguration {

    @Bean
    ServiceInstanceListSupplier healthCheckedInstances(ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()   // the gateway's RegistryReactiveDiscoveryClient
                .withHealthChecks()      // drop instances whose health path is not UP
                .build(context);
    }

    @Bean
    WebClient.Builder loadBalancerWebClientBuilder() {
        return WebClient.builder();
    }
}

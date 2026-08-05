package ug.co.smsone.gateway.route;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Applies a HEALTH-CHECKED instance list to every load-balanced service (see
 * {@link HealthCheckedLoadBalancerConfiguration}): Spring Cloud LoadBalancer probes each instance's
 * health path ({@code spring.cloud.loadbalancer.health-check.*}, pointed at {@code /actuator/health})
 * and balances only over the instances that answer UP — so a crashed instance is ejected from rotation,
 * finally consuming the {@code health-path} the service model always carried.
 *
 * <p>Health ejection only matters with more than one instance, so the whole thing is gated on the
 * {@code multi} profile: off-profile this {@code @Configuration} isn't processed, {@code @LoadBalancerClients}
 * never registers, and SCLB uses its default supplier — single-instance runs and the gateway tests are
 * untouched. The referenced config is a separate, un-scanned class so SCLB instantiates it once per
 * load-balancer client context, where the {@code serviceId} the supplier needs actually lives.
 */
@Configuration(proxyBeanMethods = false)
@Profile("multi")
@LoadBalancerClients(defaultConfiguration = HealthCheckedLoadBalancerConfiguration.class)
class GatewayLoadBalancerConfig {
}

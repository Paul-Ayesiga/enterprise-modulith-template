package ug.co.smsone.gateway.route;

import java.net.URI;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;

/**
 * Bridges the gateway's own {@link ServiceRegistry} to Spring Cloud LoadBalancer. A service with more
 * than one instance is routed as {@code lb://id}; SCLB then asks a reactive discovery client for that
 * service's instances to balance across, and this is that client. Instances are static today (from
 * {@code gateway.services[*].instances}); a real discovery backend can replace this bean later without
 * the routing or the core model changing.
 */
@Component
class RegistryReactiveDiscoveryClient implements ReactiveDiscoveryClient {

    private final ServiceRegistry registry;

    RegistryReactiveDiscoveryClient(ServiceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String description() {
        return "gateway static service registry";
    }

    @Override
    public Flux<ServiceInstance> getInstances(String serviceId) {
        return registry.find(serviceId)
                .map(service -> Flux.fromIterable(service.instances()).map(uri -> toInstance(serviceId, uri)))
                .orElseGet(Flux::empty);
    }

    @Override
    public Flux<String> getServices() {
        return Flux.fromIterable(registry.all()).map(ServiceDefinition::id);
    }

    private static ServiceInstance toInstance(String serviceId, URI uri) {
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);
        String instanceId = serviceId + "@" + uri.getHost() + ":" + port;
        return new DefaultServiceInstance(instanceId, serviceId, uri.getHost(), port, secure);
    }
}

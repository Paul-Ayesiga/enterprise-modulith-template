package ug.co.smsone.gateway.route;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistrar;
import ug.co.smsone.gateway.core.route.ServiceResolver;

/**
 * Pulls backends from every discovery source ({@link ServiceResolver}) at startup and registers them
 * ({@link ServiceRegistrar}) — so a discovered service appears automatically, with no config, and any
 * route already targeting it starts resolving. A no-op when no discovery source is on the context (the
 * gateway then runs on its configured services). A production integration (K8s/Consul/…) would also
 * re-poll or subscribe for changes; startup registration is the seam that proves the wiring.
 */
@Component
class DiscoveryRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryRegistrar.class);

    private final List<ServiceResolver> resolvers;
    private final ServiceRegistrar registrar;

    DiscoveryRegistrar(ObjectProvider<ServiceResolver> resolvers, ServiceRegistrar registrar) {
        this.resolvers = resolvers.stream().toList();
        this.registrar = registrar;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (ServiceResolver resolver : resolvers) {
            for (ServiceDefinition service : resolver.discover()) {
                registrar.register(service);
                log.info("Discovered and registered service '{}' ({} instance(s))",
                        service.id(), service.instances().size());
            }
        }
    }
}

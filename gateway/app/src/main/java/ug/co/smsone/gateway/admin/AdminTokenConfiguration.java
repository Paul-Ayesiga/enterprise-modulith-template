package ug.co.smsone.gateway.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.WebFilter;

/**
 * Authentication for the management (admin) port. Network isolation is the first line, but the route
 * table, catalog, and usage endpoints deserve a credential of their own the moment the port is
 * reachable beyond a laptop: set {@code gateway.admin.token} (env {@code GATEWAY_ADMIN_TOKEN}) and
 * every management request must carry it in {@code X-Admin-Token} — a dedicated header, because the
 * edge's JWT resource server also inspects this port and would reject a non-JWT Bearer — except
 * {@code /actuator/health}, which load balancers probe unauthenticated. Unset (the dev default), the
 * filter does not exist and behavior is unchanged.
 *
 * <p>Registered via {@link ManagementContextConfiguration} (the imports file under
 * {@code META-INF/spring}), so the filter lands on the management context whether it runs as a child
 * context on its own port or inline on the main one.
 */
@ManagementContextConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gateway.admin.token")
public class AdminTokenConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter adminTokenFilter(org.springframework.core.env.Environment environment) {
        byte[] expected = environment.getRequiredProperty("gateway.admin.token").trim()
                .getBytes(StandardCharsets.UTF_8);
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
                return chain.filter(exchange); // probes stay unauthenticated
            }
            String header = exchange.getRequest().getHeaders().getFirst("X-Admin-Token");
            byte[] presented = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, presented)) { // constant-time
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] body = "{\"error\":\"admin token required\"}".getBytes(StandardCharsets.UTF_8);
                return exchange.getResponse().writeWith(reactor.core.publisher.Mono.just(
                        exchange.getResponse().bufferFactory().wrap(body)));
            }
            return chain.filter(exchange);
        };
    }
}

package ug.co.smsone.gateway.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * The edge security layer. The gateway is a stateless OAuth2 resource server: it validates a bearer
 * JWT against the identity provider's JWKS (cached, no platform call) and rejects an invalid one with
 * 401. It does NOT enforce access here — {@code anyExchange().permitAll()} defers that to the
 * {@link EdgeAuthorizationFilter}, which applies each route's coarse policy after the route is known.
 * CORS is centralized here so services stop implementing it; sensible security headers are added.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class GatewaySecurityConfig {

    @Bean
    ReactiveJwtDecoder jwtDecoder(SecurityProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        if (properties.issuer() != null && !properties.issuer().isBlank()) {
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        }
        return decoder;
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder,
            SecurityProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(unauthorizedEntryPoint(meterRegistry.getIfAvailable()))
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
                .build();
    }

    /** An invalid/expired bearer token → 401 in the gateway's error envelope, not Security's default. */
    private static ServerAuthenticationEntryPoint unauthorizedEntryPoint(MeterRegistry meterRegistry) {
        return (exchange, ex) -> {
            if (meterRegistry != null) {
                meterRegistry.counter("gateway.auth.failures", "reason", "invalid_token").increment();
            }
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"detail\":\"A valid token is required.\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        };
    }

    private static CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(properties.cors().allowedMethods());
        configuration.setAllowedHeaders(properties.cors().allowedHeaders());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

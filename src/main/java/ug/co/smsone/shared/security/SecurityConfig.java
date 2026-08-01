package ug.co.smsone.shared.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authorization.DefaultAuthorizationManagerFactory;

/**
 * Stateless OAuth2 resource server: every request needs a valid Keycloak JWT except health probes
 * and API docs. 401/403 are rendered as the envelope, matching every other error on the wire.
 *
 * <p>The docs exposure is an accepted trade-off, not an oversight: the spec describes endpoints and
 * permission names, all of which fail closed without a token, and a discoverable contract is this
 * template's point. A deployment that considers the catalogue itself sensitive should gate these
 * matchers (or strip the routes) at its edge.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) throws Exception {
        http
                // Machine keys authenticate before the bearer path; an absent/invalid key changes
                // nothing (the request stays anonymous and authorization answers).
                .addFilterBefore(apiKeyAuthenticationFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Kill Bill's push notifications cannot do OAuth; the endpoint authenticates
                        // by the shared token in the registered callback URL (constant-time compare).
                        .requestMatchers("/api/v1/billing/killbill/notifications").permitAll()
                        // The API gateway's key-introspection seam authenticates by the shared gateway
                        // secret (constant-time compare) inside the controller, not OAuth.
                        .requestMatchers("/internal/gateway/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * The platform tier ladder — a check names the minimum tier that may pass it, and any higher tier
     * satisfies it. Published as a bean so the web layer's authorization managers pick it up, and fed
     * explicitly to the method-security handler below.
     */
    @Bean
    static RoleHierarchy platformRoleHierarchy() {
        return RoleHierarchyImpl.withRolePrefix("ROLE_")
                .role(PlatformRole.SUPERADMIN).implies(PlatformRole.ADMIN)
                .role(PlatformRole.ADMIN).implies(PlatformRole.SUPPORT)
                .build();
    }

    /**
     * Declaring a custom handler opts out of the auto-configured one — hierarchy included — so the
     * ladder has to be handed back in here, or {@code hasRole('platform-support')} would ignore it and
     * only ever match that exact authority.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(ApiPermissionEvaluator permissionEvaluator,
            RoleHierarchy roleHierarchy) {
        DefaultAuthorizationManagerFactory<MethodInvocation> authorizationManagers =
                new DefaultAuthorizationManagerFactory<>();
        authorizationManagers.setRoleHierarchy(roleHierarchy);

        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(permissionEvaluator);
        handler.setAuthorizationManagerFactory(authorizationManagers);
        return handler;
    }
}

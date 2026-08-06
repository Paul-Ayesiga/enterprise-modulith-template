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
                        // The container's ERROR dispatch (a servlet calling sendError → forward to
                        // /error) re-enters this chain with no SecurityContext — the key filter
                        // skips error dispatches — so without this rule any sendError from an
                        // authenticated area was rewritten into the entry point's 401. First
                        // victim: the MCP servlet's 405 for GET /mcp, which told remote MCP
                        // clients their valid key was bad. Boot's default chain permits exactly
                        // this; a direct request to /error is a REQUEST dispatch and stays gated.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Kill Bill's push notifications cannot do OAuth; the endpoint authenticates
                        // by the shared token in the registered callback URL (constant-time compare).
                        .requestMatchers("/api/v1/billing/killbill/notifications").permitAll()
                        // Self-service signup is pre-authentication by nature; the service itself is
                        // feature-flagged (403 when disabled) and enumeration-safe, and the shared
                        // per-IP rate limit still covers the surface.
                        .requestMatchers("/api/v1/signup", "/api/v1/signup/verify").permitAll()
                        // Pesapal IPN + browser return: secretless by vendor design — handlers only
                        // trigger a server-side re-query of the gateway, so a forged ping is inert.
                        .requestMatchers("/api/v1/payments/pesapal/ipn",
                                "/api/v1/payments/pesapal/callback").permitAll()
                        // The API gateway's key-introspection seam authenticates by the shared gateway
                        // secret (constant-time compare) inside the controller, not OAuth.
                        .requestMatchers("/internal/gateway/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(apiKeyAwareBearerTokenResolver())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * {@code Authorization: Bearer sk_…} is an API key's second spelling (remote MCP clients can
     * only send bearer headers), consumed by {@link ApiKeyAuthenticationFilter} BEFORE the bearer
     * filter runs. The JWT machinery must not see it: it would try to decode the key as a JWT and
     * 401 the very request the key filter just authenticated. A JWT can never start with
     * {@code sk_}, so nothing real is hidden.
     */
    private static org.springframework.security.oauth2.server.resource.web.BearerTokenResolver apiKeyAwareBearerTokenResolver() {
        var delegate = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        return request -> {
            String token = delegate.resolve(request);
            return token != null && token.startsWith("sk_") ? null : token;
        };
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

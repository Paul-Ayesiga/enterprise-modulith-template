package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @Test
    void mapsRealmRolesDirectlyAndNamespacesClientRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f0f7a5e-0000-0000-0000-000000000001")
                .claim("preferred_username", "paul")
                .claim("realm_access", Map.of("roles", List.of(PlatformRole.ADMIN, "USER")))
                .claim("resource_access", Map.of("smsone-web", Map.of("roles", List.of("dashboard-viewer"))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("paul");
        // Client roles are namespaced by client id — a client role named platform-admin must never
        // satisfy hasRole('platform-admin'), which is reserved for the realm role.
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_platform-admin", "ROLE_USER", "ROLE_smsone-web_dashboard-viewer");
    }

    @Test
    void clientRoleNamedPlatformAdminDoesNotBecomeRealmPlatformAdmin() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("attacker")
                .claim("resource_access", Map.of("other-client", Map.of("roles", List.of(PlatformRole.ADMIN))))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_other-client_platform-admin")
                .doesNotContain("ROLE_platform-admin");
    }

    @Test
    void fallsBackToSubjectWithoutPreferredUsername() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("service-account-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication.getName()).isEqualTo("service-account-1");
        assertThat(authentication.getAuthorities()).isEmpty();
    }
}

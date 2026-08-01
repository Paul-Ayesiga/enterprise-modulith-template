package ug.co.smsone.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The platform tier ladder, proven as a ladder rather than as three unrelated role names: each test
 * grants exactly ONE authority and asserts what it reaches. A tier that only satisfied its own
 * {@code hasRole} would pass "support reads" and fail "admin reads" — which is the bug this exists
 * to catch, since declaring a custom {@code MethodSecurityExpressionHandler} silently opts out of the
 * auto-configured hierarchy.
 *
 * <p>Endpoints stand in for their tier: {@code GET /api/v1/admin/users} is the support floor,
 * {@code PUT /api/v1/settings/{key}} is admin. The provisioning gate is off in the test profile, so
 * these assert authorization alone.
 */
@AutoConfigureMockMvc
class PlatformRoleHierarchyTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleHierarchy roleHierarchy;

    private static RequestPostProcessor only(String role) {
        return jwt().jwt(builder -> builder.subject("operator-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private void expectSupportTier(String role, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").with(only(role))).andExpect(expected);
    }

    private void expectAdminTier(String role, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        mockMvc.perform(put("/api/v1/settings/hierarchy.probe." + UUID.randomUUID())
                        .with(only(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"probe\"}"))
                .andExpect(expected);
    }

    @Test
    void supportReadsThePlatformViewButCannotChangePlatformBehaviour() throws Exception {
        expectSupportTier(PlatformRole.SUPPORT, status().isOk());
        expectAdminTier(PlatformRole.SUPPORT, status().isForbidden());
    }

    @Test
    void adminInheritsSupport() throws Exception {
        expectSupportTier(PlatformRole.ADMIN, status().isOk());
        expectAdminTier(PlatformRole.ADMIN, status().isOk());
    }

    @Test
    void superadminInheritsBothTiers() throws Exception {
        expectSupportTier(PlatformRole.SUPERADMIN, status().isOk());
        expectAdminTier(PlatformRole.SUPERADMIN, status().isOk());
    }

    @Test
    void anOrdinaryUserReachesNeitherTier() throws Exception {
        expectSupportTier("USER", status().isForbidden());
        expectAdminTier("USER", status().isForbidden());
    }

    @Test
    void forbiddenIsRenderedAsTheEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/audit").with(only("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"));
    }

    /**
     * The ladder is one-way. Without this, a hierarchy accidentally declared in reverse would still
     * pass every "higher tier reaches lower endpoint" assertion above.
     */
    @Test
    void theLadderDoesNotRunDownwards() {
        assertThat(reachableFrom(PlatformRole.SUPERADMIN))
                .contains("ROLE_" + PlatformRole.ADMIN, "ROLE_" + PlatformRole.SUPPORT);
        assertThat(reachableFrom(PlatformRole.SUPPORT))
                .containsExactly("ROLE_" + PlatformRole.SUPPORT);
    }

    private java.util.List<String> reachableFrom(String role) {
        return roleHierarchy
                .getReachableGrantedAuthorities(java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)))
                .stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .toList();
    }
}

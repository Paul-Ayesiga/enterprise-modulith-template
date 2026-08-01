package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ug.co.smsone.shared.security.PlatformRole;

/**
 * The baseline realm role is attacker-adjacent config: invite is reachable by any org member holding
 * {@code member:invite}, so a platform role configured here would let a tenant mint platform
 * operators. It fails at startup rather than at review time.
 */
class ProvisioningPropertiesTest {

    private static ProvisioningProperties withDefaultRole(String role) {
        return new ProvisioningProperties(Duration.ofHours(12), null, "smsone-web", role);
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform-support", "platform-admin", "platform-superadmin", "  platform-admin  "})
    void aPlatformRoleAsTheProvisioningBaselineIsRejected(String role) {
        assertThatThrownBy(() -> withDefaultRole(role))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a platform role");
    }

    @Test
    void anOrdinaryRoleIsAccepted() {
        ProvisioningProperties properties = withDefaultRole("USER");

        assertThat(properties.defaultRealmRole()).isEqualTo("USER");
        assertThat(properties.grantsDefaultRealmRole()).isTrue();
    }

    @Test
    void blankOrMissingGrantsNothing() {
        assertThat(withDefaultRole("   ").grantsDefaultRealmRole()).isFalse();
        assertThat(withDefaultRole(null).grantsDefaultRealmRole()).isFalse();
    }

    /** A role merely *containing* a tier name is not a platform role — the check is exact, not prefix. */
    @Test
    void aLookalikeRoleIsNotMistakenForAPlatformRole() {
        assertThat(PlatformRole.isPlatformRole("platform-admins")).isFalse();
        assertThat(withDefaultRole("platform-admins").grantsDefaultRealmRole()).isTrue();
    }
}

package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The port is pure delegation onto the audited admin path — prove the wiring, nothing else. */
class OrganizationsImplTest {

    @Test
    void delegatesToTheStandardCreatePath() {
        OrganizationService service = mock(OrganizationService.class);
        Organization organization = mock(Organization.class);
        UUID orgId = UUID.randomUUID();
        given(organization.getKcOrgId()).willReturn(orgId);
        given(service.create("acme", "Acme", "a@b.test", "Ada", null)).willReturn(organization);

        assertThat(new OrganizationsImpl(service).create("acme", "Acme", "a@b.test", "Ada", null))
                .isEqualTo(orgId);
    }
}

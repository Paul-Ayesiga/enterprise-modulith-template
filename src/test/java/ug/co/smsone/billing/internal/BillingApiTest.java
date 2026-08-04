package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The billing flows with Kill Bill mocked at the GATEWAY (the system edge — its own IT pins the
 * real wire): provisioning is idempotent and audited, a billable subscribe reconciles entitlements
 * through the subscription module's port, payment outcomes flip standing, the callback endpoint
 * refuses a bad token, and invoices proxy for both surfaces.
 */
@AutoConfigureMockMvc
class BillingApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KillBillGateway killBill;

    @Test
    void provisionSubscribeReconcileAndPaymentOutcomes() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID kbAccountId = UUID.randomUUID();
        given(killBill.ensureAccount(any(), anyString())).willReturn(kbAccountId);
        given(killBill.createSubscription(any(), anyString())).willReturn(UUID.randomUUID());
        given(killBill.subscriptions(kbAccountId)).willReturn(
                List.of(new KillBillGateway.KbSubscription("11111111-1111-1111-1111-111111111111", "pro-monthly", "ACTIVE")));
        given(killBill.accountView(kbAccountId)).willReturn(
                new KillBillGateway.KbAccountView(kbAccountId, BigDecimal.ZERO, "USD"));

        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/billing/account", orgId).with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.kbAccountId").value(kbAccountId.toString()));
        // Idempotent: the second provision returns the same linkage, no second row.
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/billing/account", orgId).with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.kbAccountId").value(kbAccountId.toString()));
        assertThat(jdbc.queryForObject(
                "select count(*) from billing_account where org_id = ?", Integer.class, orgId)).isEqualTo(1);

        // Billable subscribe: Kill Bill says pro-monthly ACTIVE → entitlements land on PRO.
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/billing/subscription", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\": \"PRO\"}")
                        .with(admin()))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/subscription", orgId).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.planCode").value("PRO"))
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));

        // Payment failed → PAST_DUE, through the callback with the right token.
        mockMvc.perform(post("/api/v1/billing/killbill/notifications")
                        .param("token", "smsone-dev-callback-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"INVOICE_PAYMENT_FAILED\",\"accountId\":\""
                                + kbAccountId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/subscription", orgId).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("PAST_DUE"));

        // Payment recovered → ACTIVE again.
        mockMvc.perform(post("/api/v1/billing/killbill/notifications")
                        .param("token", "smsone-dev-callback-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"INVOICE_PAYMENT_SUCCESS\",\"accountId\":\""
                                + kbAccountId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/subscription", orgId).with(support()))
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));

        // A wrong token is refused before anything is read.
        mockMvc.perform(post("/api/v1/billing/killbill/notifications")
                        .param("token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"INVOICE_PAYMENT_FAILED\",\"accountId\":\""
                                + kbAccountId + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action = 'billing.account_provisioned' and org_id = ?",
                Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void invoicesProxyForBothSurfacesAndUnprovisionedIs404() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedOrgRead(orgId, "billing-member");
        mockMvc.perform(get("/api/v1/orgs/{orgId}/billing/invoices", orgId)
                        .with(member(orgId, "billing-member")))
                .andExpect(status().isNotFound());

        UUID kbAccountId = UUID.randomUUID();
        given(killBill.ensureAccount(any(), anyString())).willReturn(kbAccountId);
        given(killBill.invoices(kbAccountId)).willReturn(List.of(new KillBillGateway.KbInvoice(
                UUID.randomUUID().toString(), "1001", new BigDecimal("49.00"), BigDecimal.ZERO,
                "USD", "2026-08-01", "COMMITTED")));
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/billing/account", orgId).with(admin()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/billing/invoices", orgId)
                        .with(member(orgId, "billing-member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.invoiceNumber").value("1001"));
        mockMvc.perform(get("/api/v1/admin/orgs/{orgId}/billing/invoices", orgId).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.amount").value(49.00));

        // FREE is not billable — the refusal names the billable codes.
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/billing/subscription", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\": \"FREE\"}")
                        .with(admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/plan"));
    }

    @Test
    void paymentMethodsAreManagedOnTheOrgSurfaceAndRequireOrgUpdate() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID kbAccountId = UUID.randomUUID();
        UUID pmId = UUID.randomUUID();
        seedOrgUpdate(orgId, "pm-admin");
        seedOrgRead(orgId, "pm-reader");
        given(killBill.ensureAccount(any(), anyString())).willReturn(kbAccountId);
        given(killBill.addPaymentMethod(eq(kbAccountId), eq("stripe"), eq(true), any())).willReturn(pmId);
        given(killBill.paymentMethodDetails(kbAccountId)).willReturn(
                List.of(new KillBillGateway.KbPaymentMethod(pmId, "stripe", true)));

        // Provision the billing account (a platform act).
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/billing/account", orgId).with(admin()))
                .andExpect(status().isCreated());

        // org:read cannot add a payment method...
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/payment-methods", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pluginName\":\"stripe\",\"isDefault\":true}")
                        .with(member(orgId, "pm-reader")))
                .andExpect(status().isForbidden());
        // ...but org:update can, and never sees raw card data — only a plugin reference.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/billing/payment-methods", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pluginName\":\"stripe\",\"isDefault\":true}")
                        .with(member(orgId, "pm-admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(pmId.toString()))
                .andExpect(jsonPath("$.data.attributes.pluginName").value("stripe"));

        // Any org:read member can list.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/billing/payment-methods", orgId)
                        .with(member(orgId, "pm-reader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.isDefault").value(true));

        // Set-default and remove need org:update and reach the gateway.
        mockMvc.perform(put("/api/v1/orgs/{orgId}/billing/payment-methods/{id}/default", orgId, pmId)
                        .with(member(orgId, "pm-admin")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/orgs/{orgId}/billing/payment-methods/{id}", orgId, pmId)
                        .with(member(orgId, "pm-admin")))
                .andExpect(status().isNoContent());
        verify(killBill).setDefaultPaymentMethod(kbAccountId, pmId);
        verify(killBill).deletePaymentMethod(kbAccountId, pmId);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor support() {
        return jwt().jwt(t -> t.subject("support-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private void seedOrgRead(UUID orgId, String subject) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, 'BILL', 'Bill', false, 0, now())", roleId, orgId);
        jdbc.update("insert into role_permission (role_id, permission) values (?, 'ORG_READ')", roleId);
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }

    private void seedOrgUpdate(UUID orgId, String subject) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, 'BILLADM', 'BillAdmin', false, 0, now())", roleId, orgId);
        jdbc.update("insert into role_permission (role_id, permission) values (?, 'ORG_UPDATE')", roleId);
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}

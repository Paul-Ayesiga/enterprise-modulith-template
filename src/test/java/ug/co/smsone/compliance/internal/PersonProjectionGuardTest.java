package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <strong>The convention the person projection derives its input set from, and what happens when
 * somebody breaks it.</strong>
 *
 * <p>ADR 0010 §6 item 3 says a projection travels for "every {@code person_id} referenced anywhere in
 * the schema", and the honest way to get that set is to ask the catalogue which columns hold one —
 * {@code person_id}, {@code *_person_id}, {@code created_by}, {@code updated_by}, which AGENTS §1 states
 * as the schema's own rule for cross-module soft refs. A convention is only worth as much as what
 * happens when it is broken, and here breaking it is <em>silent</em>: a {@code ticket.escalated_to uuid}
 * added next year is simply not projected, and the far side renders a blank name on exactly the row an
 * auditor asked about.
 *
 * <p>So the convention is enforced rather than trusted, and the enforcement is a list of EXCEPTIONS
 * rather than of inclusions — which is the whole difference. Forgetting an exception fails loudly here;
 * forgetting an inclusion would ship a bundle that is quietly short.
 *
 * <p><b>The DDL runs inside a silo, deliberately.</b> The subject reads {@code current_schema()}, so it
 * behaves identically for a pooled tenant and a siloed one — and a column added to {@code tenant_pool}
 * would be a column every other test class in this container then shares. {@link TenantSilos} drops the
 * schema afterwards whatever this test does, so the stray column cannot outlive the method that made it
 * even if an assertion fails first.
 */
class PersonProjectionGuardTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private PersonProjector projector;

    @Autowired
    private JdbcTemplate jdbc;

    /** The schema as it stands classifies cleanly, which is what makes the next test's failure mean something. */
    @Test
    void everyUuidColumnInTheTenantTierIsAlreadyAccountedFor() {
        UUID orgId = UUID.randomUUID();
        silos.place(orgId);

        TenantContext.runAs(orgId, projector::requireEveryUuidColumnIsAccountedFor);
    }

    /**
     * A uuid column that is neither a key, nor {@code org_id}, nor a foreign key, nor a person reference
     * by the naming convention stops the bundle and names itself. The alternative — projecting whatever
     * the convention happens to catch — is a bundle that is short by an unknown number of humans and
     * says nothing about it.
     */
    @Test
    void aStrayUuidColumnStopsTheBundleInsteadOfSilentlyLosingAHuman() {
        UUID orgId = UUID.randomUUID();
        String silo = silos.place(orgId);
        jdbc.execute("alter table " + silo + ".ticket add column escalated_to uuid");

        assertThatThrownBy(() -> TenantContext.runAs(orgId,
                projector::requireEveryUuidColumnIsAccountedFor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ticket.escalated_to")
                .hasMessageContaining("NON_PERSON_UUID_COLUMNS");
    }

    /**
     * And the other direction: an exemption for a column the schema no longer has. A stale exemption is
     * worse than none, because it silently covers the next column that takes the name — and the name it
     * covers would be one nobody re-argued.
     */
    @Test
    void anExemptionForAColumnTheSchemaNoLongerHasFailsToo() {
        UUID orgId = UUID.randomUUID();
        String silo = silos.place(orgId);
        jdbc.execute("alter table " + silo + ".billing_account drop column kb_account_id");

        assertThatThrownBy(() -> TenantContext.runAs(orgId,
                projector::requireEveryUuidColumnIsAccountedFor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("billing_account.kb_account_id")
                .hasMessageContaining("stale exemption");
    }

    /**
     * The classifier's one incidental answer, pinned so a future refactor cannot take it away by
     * accident: {@code user_device_trust.device_id} is a cross-tier soft ref with no foreign key and no
     * exemption, and it classifies only because V51 made it half of that table's primary key.
     */
    @Test
    void theCrossTierDeviceRefClassifiesThroughItsPrimaryKeyAndNotThroughAnExemption() {
        UUID orgId = UUID.randomUUID();
        silos.place(orgId);

        assertThat(TenantContext.callAs(orgId, () -> jdbc.queryForList("""
                select a.attname from pg_constraint con
                  join pg_class c on c.oid = con.conrelid
                  join pg_namespace n on n.oid = c.relnamespace
                  join unnest(con.conkey) as k(attnum) on true
                  join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
                 where con.contype = 'p' and n.nspname = current_schema()
                   and c.relname = 'user_device_trust'
                """, String.class)))
                .containsExactlyInAnyOrder("device_id", "org_id");
    }
}

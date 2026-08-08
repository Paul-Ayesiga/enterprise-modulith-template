package ug.co.smsone.shared.tenancy.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <strong>The promoter refuses at the silo ceiling</strong> (ADR 0010 §8 Q1), and the refusal carries
 * the derivation with it.
 *
 * <p>The ceiling ships at 200 and this class runs at 1. That is not a shortcut: creating two hundred
 * silos would cost two hundred Flyway replays and ~48,000 relations to assert one comparison, and the
 * number being configurable is itself the deliverable — §8 Q1 calls 200 "<em>the one number in the
 * document I would most want re-measured before it is load-bearing</em>", and a constant would make
 * re-measuring it a deploy. Running the suite at 1 exercises exactly the code path an operator meets at
 * 200.
 *
 * <p>Its own {@code @TestPropertySource} means its own Spring context, which is the price of testing a
 * configured bound at all. One context, cached for the class.
 */
@TestPropertySource(properties = "app.tenancy.promotion.max-silos=1")
class TenantPromotionCeilingTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantPromoter promoter;

    @Autowired
    private TenantPlacements placements;

    @Test
    void theSecondSiloIsRefusedWithTheDerivationTheOperatorNeeds() {
        // No placement row for the first one: TenantSilos.place() reserves the tenant itself and
        // deliberately declines an org that is already serving somewhere (moving one of those is
        // promotion). It builds the silo without copying anything, which is exactly the fleet state the
        // ceiling counts — one schema every cross-tenant fan-out now has to branch over.
        UUID first = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        silos.place(first);
        UUID second = pooledOrganization();

        assertThatThrownBy(() -> promoter.promote(second))
                .isInstanceOf(TenantPromotionException.class)
                .hasMessageContaining("already holds 1 tenant silos and the ceiling is 1")
                // The derivation goes where the operator will read it — §8 Q1's two measured costs, the
                // arithmetic at the ceiling, and what to do instead of raising it blindly.
                .hasMessageContaining("0.5-0.66 ms per branch")
                .hasMessageContaining("2.004 entries per branch")
                .hasMessageContaining("~130 ms of planning and ~403 lock entries")
                .hasMessageContaining("platform.ticket_index")
                .hasMessageContaining("docs/runbooks/tenant-promotion.md");

        assertThat(TenantContext.callAsPlatform(() -> placements.find(second)).orElseThrow().schemaName())
                .describedAs("a refused promotion leaves the tenant exactly where it was")
                .isEqualTo(TenantSchemas.TENANT_POOL);
        assertThat(TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select count(*) from platform.tenant_freeze where org_id = ?", Long.class, second)))
                .describedAs("and holds no freeze — the refusal happens before anything is taken")
                .isZero();
    }

    private UUID pooledOrganization() {
        UUID id = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        TenantContext.runAsPlatform(() -> placements.announce(id, TenantSchemas.TENANT_POOL));
        return id;
    }
}

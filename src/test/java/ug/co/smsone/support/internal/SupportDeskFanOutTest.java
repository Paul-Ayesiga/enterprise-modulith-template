package ug.co.smsone.support.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>The platform support desk, with one organization in its own schema and the rest pooled</b>
 * (ADR 0010 §5.1, §5.2, §7 Phase 5).
 *
 * <p>Three surfaces, one failure. {@code SlaEscalationJob}, the cross-tenant queue and every
 * single-ticket admin route used to pin {@code tenant_pool} and read one schema, which was right
 * exactly while one schema held every ticket. After a promotion the same code escalates nothing for the
 * promoted tenant, lists none of its tickets, and answers 404 for every one of them — and does all three
 * without an exception, a log line or a metric moving. {@code SupportFlowTest} proves the lifecycle
 * within one schema and would have passed throughout.
 */
@AutoConfigureMockMvc
class SupportDeskFanOutTest extends AbstractIntegrationTest {

    /**
     * Far enough ahead of any row another test class writes that this class's probes are the head of
     * {@code created_at desc} whatever else shares the container. See {@link #insertOpenTicket}.
     */
    private static final Instant QUEUE_EPOCH = Instant.parse("2099-01-01T00:00:00Z");

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SlaEscalationJob escalationJob;

    /**
     * The job the ADR names as the one the split hurts most (§5.2): {@code lockBreached} is unqualified,
     * so a sweep that pinned only the pool kept returning the pool's breaches and reported a healthy run
     * while a promoted tenant's SLA clock ran forever.
     */
    @Test
    void escalatesABreachInASiloAndOneInThePoolInTheSameSweep() {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);

        UUID pooledTicket = insertBreachedTicket(pooled);
        UUID siloedTicket = insertBreachedTicket(siloed);
        // Schema-qualified, before the sweep: the siloed ticket is really in t_<hex> and really not in
        // the pool. insertTicket writes and escalated() reads through TenantContext, so without this
        // probe both would land in tenant_pool if routing ever went away, the pooled visit would do the
        // work, and this test would go green with the silo empty.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", siloedTicket);

        runEscalation();

        assertThat(escalated(pooled, pooledTicket)).as("the pooled breach escalates, as it always did")
                .isTrue();
        assertThat(escalated(siloed, siloedTicket))
                .as("and so does the siloed one. Before the fan-out this ticket sat past its resolution "
                        + "due indefinitely, never escalated, never counted, never notified")
                .isTrue();
    }

    /**
     * The operator's queue is keyset-paginated across every tenant. The merge has to be a real N-way
     * merge over the same cursor — see {@code TicketFanOut} — and the falsifiable half is that a
     * promoted tenant's ticket appears in it at all.
     */
    @Test
    void theCrossTenantQueueListsTicketsFromASiloAndFromThePool() throws Exception {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);
        UUID pooledTicket = insertOpenTicket(pooled);
        UUID siloedTicket = insertOpenTicket(siloed);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", siloedTicket);

        List<String> ids = queuePage("?status=OPEN&page[size]=100");

        assertThat(ids).contains(pooledTicket.toString());
        assertThat(ids)
                .as("a promoted tenant's ticket must still reach the support queue — dropping it is "
                        + "invisible to the operator, who simply never sees the customer's problem")
                .contains(siloedTicket.toString());
    }

    /**
     * The merge is ordered by the same {@code created_at desc, id desc} each branch ordered by, so the
     * page a caller gets is the globally newest — not the pool's newest with the silo's appended.
     */
    @Test
    void theMergedQueueIsOrderedAcrossHomesAndNotConcatenated() throws Exception {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);
        // Interleaved in time: pool, silo, pool. A concatenating implementation would emit the two
        // pooled tickets and then the siloed one whatever their timestamps say.
        UUID oldest = insertOpenTicket(pooled, QUEUE_EPOCH.plus(Duration.ofMinutes(10)));
        UUID middle = insertOpenTicket(siloed, QUEUE_EPOCH.plus(Duration.ofMinutes(20)));
        UUID newest = insertOpenTicket(pooled, QUEUE_EPOCH.plus(Duration.ofMinutes(30)));
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", middle);

        List<String> ids = queuePage("?status=OPEN&page[size]=100");

        assertThat(ids).containsSubsequence(newest.toString(), middle.toString(), oldest.toString());
    }

    /**
     * The cursor has to survive the merge. Each branch is scrolled from the SAME position and the next
     * cursor is taken from the winning branch's own window, so paging must be exact across homes — no
     * row seen twice, none skipped.
     */
    @Test
    void pagingThroughTheMergedQueueSkipsNothingAndRepeatsNothing() throws Exception {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);
        // A whole year past this class's other probes: silos are dropped after each test but POOLED
        // rows are not, so the four tickets walked here have to outrank the leftovers of the two tests
        // above or a page-size-1 walk would spend its pages on them.
        Instant epoch = QUEUE_EPOCH.plus(Duration.ofDays(365));
        UUID a = insertOpenTicket(pooled, epoch.plus(Duration.ofMinutes(10)));
        UUID b = insertOpenTicket(siloed, epoch.plus(Duration.ofMinutes(20)));
        UUID c = insertOpenTicket(pooled, epoch.plus(Duration.ofMinutes(30)));
        UUID d = insertOpenTicket(siloed, epoch.plus(Duration.ofMinutes(40)));
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", b);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", d);

        List<String> walked = new java.util.ArrayList<>();
        String query = "?status=OPEN&page[size]=1";
        for (int page = 0; page < 6; page++) {
            String body = mockMvc.perform(get("/api/v1/admin/tickets" + query).with(support()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            walked.addAll(JsonPath.read(body, "$.data[*].id"));
            String next = JsonPath.read(body, "$.meta.page.nextCursor");
            if (next == null) {
                break;
            }
            query = "?status=OPEN&page[size]=1&page[after]=" + next;
        }

        assertThat(walked).doesNotHaveDuplicates();
        assertThat(walked)
                .as("every ticket appears exactly once across the pages, in the global order — a merge "
                        + "whose comparator disagreed with Postgres' would skip or repeat a row here, "
                        + "and only when two timestamps collided")
                .containsSubsequence(d.toString(), c.toString(), b.toString(), a.toString());
    }

    /**
     * A ticket id carries no tenant, so the desk has to find the home that holds it. Before the fan-out
     * a promoted tenant's ticket was simply a 404 — indistinguishable, to the operator, from one the
     * customer had deleted.
     */
    @Test
    void aSingleTicketInASiloIsReachableByIdFromTheAdminDesk() throws Exception {
        UUID siloed = organization();
        silos.place(siloed);
        UUID ticket = insertOpenTicket(siloed);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "ticket", "id", ticket);

        mockMvc.perform(get("/api/v1/admin/tickets/{id}", ticket).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.orgId").value(siloed.toString()));
    }

    /** A ticket that exists in no home is still a 404 — the fan-out must not turn "not found" into 500. */
    @Test
    void anUnknownTicketIsStillANotFoundAfterEveryHomeHasBeenProbed() throws Exception {
        UUID siloed = organization();
        silos.place(siloed);

        mockMvc.perform(get("/api/v1/admin/tickets/{id}", UUID.randomUUID()).with(support()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    }

    private List<String> queuePage(String query) throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/tickets" + query).with(support()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[*].id");
    }

    /**
     * ShedLock intercepts a direct call as well as a cron one, and holds the lock afterwards — so the
     * second test in this class would be skipped in silence and pass for the wrong reason.
     */
    private void runEscalation() {
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "support-sla-escalation");
        escalationJob.escalateBreaches();
    }

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "desk-" + UUID.randomUUID());
    }

    private UUID insertBreachedTicket(UUID orgId) {
        return insertTicket(orgId, "OPEN", Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(2)));
    }

    private UUID insertOpenTicket(UUID orgId) {
        return insertOpenTicket(orgId, QUEUE_EPOCH);
    }

    /**
     * <b>{@code createdAt} is deliberately in the future, and that is what makes the queue assertions
     * deterministic.</b> One Postgres container serves every test class, so {@code tenant_pool} already
     * holds tickets other classes opened at {@code now()}. The queue is ordered {@code created_at desc},
     * so probes stamped "now" would sort behind whatever ran before them and a page-size-1 walk would
     * never reach them. Stamping them past every real row puts them at the head of the ordering
     * regardless of what else is in the container — which is the only way to assert on a MERGE without
     * asserting on the rest of the suite.
     *
     * <p>Due far in the future too, so the escalation sweep in this class's other tests cannot touch
     * them.
     */
    private UUID insertOpenTicket(UUID orgId, Instant createdAt) {
        return insertTicket(orgId, "OPEN", QUEUE_EPOCH.plus(Duration.ofDays(30)), createdAt);
    }

    /**
     * Written on the org's OWN axis — which for a placed org is its silo. That is the entire fixture:
     * every read under test is unqualified, so a desk that cannot follow the tenant into its schema
     * finds nothing and says nothing.
     */
    private UUID insertTicket(UUID orgId, String status, Instant resolutionDueAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        UUID opener = EdgeSeed.person(jdbc, "desk-opener-" + id);
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into ticket (id, org_id, opener_person_id, subject, priority, status,
                                    first_response_due_at, resolution_due_at, escalated, version, created_at)
                values (?, ?, ?, ?, 'P3', ?, ?, ?, false, 0, ?)
                """, id, orgId, opener, "Fan-out probe " + id, status,
                Timestamp.from(resolutionDueAt), Timestamp.from(resolutionDueAt), Timestamp.from(createdAt)));
        return id;
    }

    private boolean escalated(UUID orgId, UUID ticketId) {
        return Boolean.TRUE.equals(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select escalated from ticket where id = ?", Boolean.class, ticketId)));
    }

    /** The operator's token: a platform role and a person their subject resolves to. */
    private RequestPostProcessor support() {
        String subject = "desk-operator-" + UUID.randomUUID();
        EdgeSeed.person(jdbc, subject);
        return jwt().jwt(token -> token.issuer(EdgeSeed.ISSUER).subject(subject))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"));
    }
}

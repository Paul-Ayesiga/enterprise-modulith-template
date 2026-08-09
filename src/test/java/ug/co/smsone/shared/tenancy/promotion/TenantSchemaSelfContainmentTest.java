package ug.co.smsone.shared.tenancy.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.compliance.internal.TenantBundleArtifact;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.SchemaResult;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.ExtractionVerdicts;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <strong>ADR 0010 §7 Phase 6's gate: a tenant schema is a whole tenant, and the proof is that it is
 * dumped out of this database, restored into another one that has never heard of the platform, and
 * still serves.</strong> The test AGENTS §1 names as the enforcement of its second foreign-key clause —
 * "<em>no FK connects a PLATFORM-tier table to a TENANT-tier table, even when both are in the same
 * module</em>" — and the three parts ADR 0010 §6 asks for, in its order:
 *
 * <ol>
 *   <li>{@link #noForeignKeyLeavesItsSchemaOrJoinsTwoTiersInAnyTenantHome()} — <b>(a)</b> no foreign key
 *       crosses the boundary, tiers read out of {@code docs/DATA_MODEL.md};</li>
 *   <li>{@link #aSiloHoldsEveryTenantTierTableTheDocumentNamesAndNoPlatformTierOne()} — <b>(b)</b> the
 *       tenant schema holds exactly the tenant tier;</li>
 *   <li>{@link #aDumpedTenantRestoresWholeAndTheShippedBundlersArtifactThenServesIt()} — <b>(c)</b> the
 *       dump and the restore, actually performed, <em>twice and by the two routes ADR 0010 §6 names</em>:
 *       item 1 taken by hand with {@code pg_dump -n t_<hex>}, and then the shipped
 *       {@code compliance.internal.TenantBundler}'s own emitted script. §6: "<em>Without (c) the design
 *       drifts the first time someone adds a table.</em>"</li>
 * </ol>
 *
 * <p>A fourth, {@link #everyPlatformTierTableThatCanNameAnOrganizationCarriesAnExtractionVerdict()}, is
 * not in §6's list and is the one that catches what (a) and (b) structurally cannot: a table that is
 * correctly platform-tier and still holds rows naming one organization. Nothing about its SCHEMA is
 * wrong, so no assertion about schemas will ever fail on it — but its rows either travel with the tenant
 * or are lost at extraction, and that is a decision somebody has to make. See its javadoc; it is where
 * this pass found real gaps.
 *
 * <h2>What the acceptance test says, and how much of it a test can honestly do</h2>
 *
 * <p>§6, verbatim: "<em>stand the extracted deployment up with the platform completely unreachable — no
 * network route at all — then log in as a member, list members, open a ticket, run an export, and let
 * the nightly jobs run once. Anything that fails names a table on the wrong side of the boundary.</em>"
 *
 * <p><b>Performed here.</b> The unreachability is literal rather than simulated: the extracted
 * deployment is a <em>second Postgres container</em>, and every statement after the restore opens
 * against it alone. It holds no row this test did not put there, its {@code tenant_pool} holds no tables
 * at all, and there is no connection, route or credential from it back to the origin — so anything the
 * tenant needed from the platform and did not carry simply is not there. Inside it: the authorization
 * walk that decides what a member may do, the member list, a ticket opened after the restore, an export
 * driven by the real {@link TenantTierTables} planner over the restored schema, and the one nightly-job
 * statement shape that crosses the tier ({@code SoftDeletePurgeJob}'s {@code platform.legal_hold}
 * anti-join).
 *
 * <p><b>And what it is served FROM is the shipped bundler's own artifact.</b> Every one of those
 * serve-side claims used to run on top of ninety lines of hand-written {@code copyRows(...)} in this
 * file, which is why the whole gate would have stayed green with {@code TenantBundler} deleted — and
 * why the two artifacts could ship contradicting each other about {@code legal_hold} and
 * {@code api_usage_daily} with nothing failing (ADR 0010 §7 Phase 6). The far side is now filled by
 * {@code TenantBundler} → {@code BundleScriptWriter} → {@code psql}, reached through
 * {@link TenantBundleArtifact}, whose javadoc records why the seam is a test class in the bundler's own
 * package rather than a widened production type or an invented HTTP route.
 *
 * <p><b>Not performed here, and nobody should read this as the full rehearsal.</b> No Spring context is
 * booted against the extracted database, so the filter chain, the token validation and the HTTP surface
 * are untested on that side — "log in as a member" is rehearsed as the database half of a login (the
 * authorization walk) and not the token half. The nightly jobs are rehearsed as one statement shape, not
 * by running the fourteen {@code @SchedulerLock} jobs. And three of §6's eleven bundle items are outside
 * a database test entirely: the object store under {@code doc/o/<orgId>/} and {@code exch/o/<orgId>/}
 * (item 7), the re-minted API keys (item 8) and the IdP decision (item 9). A test that claimed the whole
 * gate and did less would be worse than no test, so the boundary is written down rather than implied.
 *
 * <h2>Why the tiers are read out of the document and not out of the database</h2>
 *
 * <p>ADR 0010 §6 requires it, and the reason is that the database cannot answer the question: a table's
 * tier is decided by who OWNS its rows, and §2 lists three tables ({@code ticket_message},
 * {@code org_group_member}, {@code exchange_job_error}) that hold tenant data with no {@code org_id} of
 * their own. Reading the tier back out of the schema the migrations built would make this test agree
 * with the migrations by construction.
 *
 * <p>This is the <em>third</em> reader of those {@code **Tier:**} lines, and the duplication is the
 * point rather than an oversight — {@code PlatformSchemaQualificationTest}'s copy says so in its own
 * comment. Each test parses the authoritative document independently, so a bug in one parse cannot make
 * three tests agree with each other about something the document does not say.
 *
 * <h2>Siblings, and what each one owns</h2>
 *
 * <p>{@code TenancyTierBoundaryTest} makes the same two claims about the two schemas that always exist:
 * that every FK inside {@code platform} and {@code tenant_pool} stays in its tier, and that a silo holds
 * what the pool holds. This test is the wider form of both — every FK in every tenant home <em>and in a
 * restored deployment</em>, and the silo compared against the DOCUMENT rather than against the pool, so
 * a tenant sequence that drifted from the recorded tiers fails here even though both schemas were built
 * from it. {@code TenantPromotionTest} proves the rows move; this proves that what they move into is a
 * tenant. {@code TenantTierTablesPlanTest} proves the copy plan covers the schema.
 */
class TenantSchemaSelfContainmentTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ the document

    private static final Path DATA_MODEL = Path.of("docs", "DATA_MODEL.md");

    /** Table sections are the only {@code ###} headings in the document, and are named exactly. */
    private static final Pattern TABLE_HEADING = Pattern.compile("^### ([a-z_][a-z0-9_]*)\\s*$");

    private static final Pattern TIER_LINE = Pattern.compile("^\\*\\*Tier:\\*\\*\\s+(.+)$");

    /**
     * A tier line is {@code <tier> — <the rule deciding which copy a row belongs to>}. Only the part
     * before the em dash is machine-readable, so the prose can be rewritten without touching this test.
     */
    private static final String CLAUSE_SEPARATOR = "—";

    /** ADR 0010 §2's three homes, spelled as {@code docs/DATA_MODEL.md} spells them. */
    private static final String TIER_PLATFORM = "platform";
    private static final String TIER_TENANT = "tenant";
    private static final String TIER_SPLIT = "platform + tenant";

    private static final Set<String> KNOWN_TIERS = Set.of(TIER_PLATFORM, TIER_TENANT, TIER_SPLIT);

    /** Flyway's own bookkeeping. It describes the schema rather than belonging to any tier. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    /** ADR 0010 §3.1's silo name, as a Postgres regex — asked of the catalogue, never of a kept list. */
    private static final String SILO_SCHEMA_PATTERN = "^t_[0-9a-f]{32}$";

    // ------------------------------------------------------------------ the extracted deployment

    /**
     * <strong>The extracted deployment's database.</strong> A second container is the whole reason this
     * test can make §6's claim at all: "the platform is unreachable" is not a flag or a mock here, it is
     * the fact that this Postgres has never been told the origin exists and every connection opened
     * after the restore points at it.
     *
     * <p>Class-scoped, and therefore <em>closed</em> in {@link #stopTheExtractedDeployment()} — the
     * opposite of {@code AbstractIntegrationTest.POSTGRES}, whose close would take the database out from
     * under every other cached Spring context in the run. This one is used by one class, so leaving it
     * for Ryuk would just hold a second Postgres's memory for the rest of the suite. The
     * {@code resource} suppression is the same ECJ complaint that field points at, with the opposite
     * resolution.
     *
     * <p><b>The image is READ OFF the origin container rather than spelled again.</b> Not tidiness: this
     * test's whole verdict is "the restore succeeded, therefore the boundary holds", and a restore can
     * also fail because {@code pg_dump} on one version emitted syntax {@code psql} on an older one does
     * not accept. A second literal would let the two drift the day someone bumps
     * {@code AbstractIntegrationTest.POSTGRES}, and the resulting red would name a table on the wrong
     * side of the boundary while actually meaning version skew — the most expensive wrong answer this
     * file can give. Reading the origin's image makes that failure mode unreachable. Safe at class-init
     * time because a superclass is initialized before its subclass, so {@code POSTGRES} is already built.
     */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer EXTRACTED =
            new PostgreSQLContainer(POSTGRES.getDockerImageName());

    /** Where the hand-run {@code pg_dump} lands inside {@link #EXTRACTED} before {@code psql} replays it. */
    private static final String DUMP_PATH = "/tmp/tenant-extraction.sql";

    /** Where the shipped bundler's script lands. A second path so a psql failure names which artifact. */
    private static final String BUNDLE_PATH = "/tmp/tenant-bundle.sql";

    /**
     * The bundle's ONE editable line. {@code BundleScriptWriter} emits
     * {@code set search_path to tenant_pool, ext;} above a comment telling the operator to change it if
     * this deployment places its single tenant in a silo — and the placement row it writes at the end
     * derives {@code schema_name} from {@code current_schema()} precisely so that editing this line
     * moves the registry with it. This test performs that edit, which is what makes the derivation
     * something asserted rather than something argued: {@code TenantBundleTest} restores into
     * {@code tenant_pool}, where the default and the truth coincide and a hard-coded name would pass.
     */
    private static final Pattern SEARCH_PATH_LINE = Pattern.compile("(?m)^set search_path to .+;$");

    /**
     * One worker. The runner does two serial things here — {@code migratePlatform}, and a fan-out of
     * exactly one schema — so a wider pool would open connections nothing uses.
     */
    private static final int MIGRATION_WORKERS = 1;

    // ------------------------------------------------------------------ the origin

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Only ever handed to {@link TenantBundleArtifact#take}, which resolves the package-private
     * {@code TenantBundler} bean out of it. Injected rather than reached through a static because that
     * is the one thing this test needs from the {@code compliance} module and it should be visible in
     * the field list that it needs it.
     */
    @Autowired
    private ApplicationContext beans;

    @BeforeAll
    static void startTheExtractedDeployment() {
        EXTRACTED.start();
    }

    @AfterAll
    static void stopTheExtractedDeployment() {
        EXTRACTED.stop();
    }

    // ------------------------------------------------------------------ (a)

    /**
     * <strong>ADR 0010 §6 part (a), and AGENTS §1's second foreign-key clause.</strong> No foreign key
     * joins two tiers, and none reaches out of the schema it lives in — in {@code platform}, in
     * {@code tenant_pool} and in a real {@code t_<32hex>} silo.
     *
     * <p><b>The assertion is on the TIER axis, not the module axis, and that is the entire point.</b>
     * AGENTS §1's original rule was stated on modules, and all five foreign keys ADR 0010 §6 had to cut
     * <em>passed</em> it: {@code org_subscription.plan_id → plan} never leaves {@code subscription/} and
     * {@code user_device_trust.device_id → user_device} never leaves {@code access/}. A module-axis rule
     * is not failing to catch those — it is never looking at them. V53 cut all five; this is what stops
     * a sixth.
     *
     * <p>The two halves catch different mistakes. <b>Schema</b> is the physical one: a constraint
     * reaching out of the schema is exactly what stops {@code pg_dump -n <schema>} producing something
     * restorable, and part (c) below is where that stops being an argument. <b>Tier</b> still bites where
     * the physical check cannot — two tables can share a schema today and belong to different tiers, and
     * that key becomes a cross-<em>database</em> key at Phase 7 with nothing having moved in between.
     *
     * <p>Deliberately no assertion on the NUMBER of foreign keys. The invariant is that none crosses, not
     * that there are twelve; a count would fail on the next legitimate intra-tier key and teach whoever
     * hit it that the number was the rule. What is asserted instead is that a silo genuinely contributed
     * keys, because a query that had quietly stopped seeing tenant homes would report no crossings
     * forever.
     */
    @Test
    void noForeignKeyLeavesItsSchemaOrJoinsTwoTiersInAnyTenantHome() {
        String silo = silos.place(EdgeSeed.organization(
                jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID()));

        List<String> homes = new ArrayList<>(List.of(TenantSchemas.PLATFORM, TenantSchemas.TENANT_POOL));
        homes.addAll(siloSchemas(jdbc));
        assertThat(homes).describedAs("the silo this test just placed must be among the schemas checked")
                .contains(silo);

        Crossings crossings = foreignKeyCrossings(jdbc, homes, tiers());

        assertThat(crossings.schemasThatDeclaredKeys())
                .describedAs("every tenant home must declare foreign keys of its own; a result missing one"
                        + " would pass this test for the wrong reason")
                .contains(TenantSchemas.PLATFORM, TenantSchemas.TENANT_POOL, silo);
        assertThat(crossings.leavingTheirSchema())
                .describedAs("foreign keys reaching out of their own schema — a tenant carrying one of"
                        + " these cannot be dumped and restored on its own (ADR 0010 §6)")
                .isEmpty();
        assertThat(crossings.joiningTwoTiers())
                .describedAs("foreign keys joining a PLATFORM-tier table to a TENANT-tier one — cut them"
                        + " to soft refs, and replace whatever the constraint was quietly doing"
                        + " (AGENTS §1, ADR 0010 §6's five)")
                .isEmpty();
    }

    // ------------------------------------------------------------------ (b)

    /**
     * <strong>ADR 0010 §6 part (b): every tenant-tier table is in the tenant schema, and no platform-tier
     * table is.</strong> Read off the {@code **Tier:**} lines, so what is being checked is that the
     * migrations agree with the recorded decision — not that two schemas built by the same sequence agree
     * with each other, which is {@code TenancyTierBoundaryTest}'s claim and a strictly weaker one.
     *
     * <p>Both directions fail differently. A silo MISSING a tenant table is loud: the tenant's next
     * request 500s on {@code relation "…" does not exist}. A silo CARRYING a platform table is the quiet
     * one — nothing reads it, because the platform copy is reached by explicit qualification, so it
     * survives every other test in the suite and surfaces only on extraction day, as a
     * {@code pg_dump -n t_<hex>} that lifts a slice of the platform's catalogue out with one tenant's
     * data.
     */
    @Test
    void aSiloHoldsEveryTenantTierTableTheDocumentNamesAndNoPlatformTierOne() {
        String silo = silos.place(EdgeSeed.organization(
                jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID()));

        assertThat(baseTablesIn(jdbc, silo))
                .describedAs("%s must hold exactly the tables %s assigns to the tenant tier — a table in"
                        + " the wrong migration directory resolves perfectly at runtime and is only ever"
                        + " seen on extraction day (ADR 0010 §2)", silo, DATA_MODEL)
                .isEqualTo(tenantTierTables());
    }

    // ------------------------------------------------------------------ the anti-rot enumeration

    /**
     * <strong>The rot (a) and (b) cannot see: a table whose tier is right and whose ROWS still name one
     * organization.</strong> {@code platform.legal_hold} is platform-tier for a reason §2 states plainly
     * — there must be exactly ONE set of holds — and it carries an {@code org_id}. Nothing about its
     * schema is wrong. But an ORG-scoped hold either travels in the extraction bundle or it does not, and
     * if it does not, the extracted deployment's {@code SoftDeletePurgeJob} reads its own empty
     * {@code legal_hold}, matches nothing, and resumes hard-deleting data a court said to keep — which is
     * the failure that table exists to prevent, reproduced by moving the tenant instead of by forgetting
     * the join.
     *
     * <p>So every platform-tier table that CAN name an organization gets a verdict, and a table added in
     * V60 with an {@code org_id} on the platform side fails the build until its author says which one.
     * Same shape as {@code SoftDeletePurgeJobIntegrationTest
     * .purgeOrderCoversEverySoftDeletableEntity} and {@code TenantPromotionEvictionTest}'s
     * cache enumeration, and for the same reason: the decision is cheap to make and invisible to skip.
     *
     * <p><b>The verdicts themselves are no longer written here.</b> They were, and they shipped
     * contradicting {@code TenantBundlePlan} on {@code legal_hold} and {@code api_usage_daily} without a
     * single failing test, because this file hand-carries its own platform half and never invokes the
     * bundler. The judgement lives in {@code TenantBundlePlan}; {@link ExtractionVerdicts} mirrors the
     * part this file needs in the same vocabulary, and
     * {@code compliance.internal.ExtractionVerdictAgreementTest} fails if the mirror ever says something
     * the shipped plan does not. What stays here is the question only a live catalogue can answer: is
     * there an org-scoped platform table nobody has answered for?
     *
     * <p>The seven split tables are deliberately not here. Their answer is already structural — the
     * tenant half is in the dump, the platform half stays — and it is asserted by (b).
     *
     * <p><b>Seven of the verdicts are newer than the document</b> — each marked {@code NOT IN §6's list}
     * at its entry in {@link ExtractionVerdicts}, so the count and the entries cannot drift apart. ADR
     * 0010 §6's eleven-item bundle list predates {@code queue_signal} (V56), {@code tenant_placement}
     * (V57), {@code tenant_freeze} (V58) and {@code api_key_prefix_reservation} (V59), and never covered
     * {@code legal_hold}, {@code api_usage_daily} or {@code org_membership_index}. Each verdict cites the
     * evidence that decides it rather than inventing one, and the §6 list needs the same seven added
     * to it.
     *
     * <p><b>The candidate set SUBTRACTS the tenant tier rather than INTERSECTING the platform tier, and
     * the difference is not cosmetic.</b> Both spellings exclude the same five split tables the query can
     * actually return (of the seven, only {@code audit_log}, {@code document}, {@code exchange_job},
     * {@code integration} and {@code maintenance_window} carry an {@code org_id} at all), but an
     * intersection also excludes — silently — any table {@code docs/DATA_MODEL.md} does not mention at
     * all, which is precisely the table most likely to be missing a verdict: the one added this week.
     * That is not hypothetical. V59 added {@code api_key_prefix_reservation} to the platform schema with
     * an {@code org_id}, and while the document had no section for it the intersection dropped it from
     * the candidate set and this test passed without an answer for it. Subtracting the tenant tier keeps
     * an undocumented table IN the set, so it demands a verdict from the day its migration lands.
     * ({@code TenancyTierBoundaryTest.everyTableCarriesATierAndSitsInExactlyTheSchemasThatTierNames} is
     * what fails on the missing document section itself; this assertion must not be the second test that
     * quietly depends on that one having already passed.)
     */
    @Test
    void everyPlatformTierTableThatCanNameAnOrganizationCarriesAnExtractionVerdict() {
        Set<String> canNameAnOrganization = new TreeSet<>(jdbc.queryForList(
                """
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                  join pg_attribute a on a.attrelid = c.oid and a.attname = 'org_id'
                                     and a.attnum > 0 and not a.attisdropped
                 where n.nspname = ? and c.relkind = 'r'
                """,
                String.class, TenantSchemas.PLATFORM));
        // The seven split tables live in `platform` too and five of them carry an org_id. Their rows'
        // verdict is structural, so they come out — but by NAME, off the document's tenant tier, never
        // by "was it recorded as platform". See the javadoc: the two are not the same subtraction.
        canNameAnOrganization.removeAll(tenantTierTables());

        assertThat(canNameAnOrganization)
                .describedAs("the catalogue must actually hold platform-tier tables with an org_id, or"
                        + " this test passes vacuously")
                .isNotEmpty();
        assertThat(ExtractionVerdicts.tables())
                .describedAs("every platform-tier table carrying an org_id needs a recorded answer to"
                        + " 'what happens to these rows when the tenant leaves'. A new one is not a"
                        + " schema mistake and nothing else in this build will notice it — see"
                        + " legal_hold, whose silence means a court order stops applying (ADR 0010 §6)")
                .containsExactlyInAnyOrderElementsOf(canNameAnOrganization);
    }

    /**
     * How many of this organization's rows the far side is allowed to hold in one org-scoped platform
     * table, given the verdict on it — the whole judgement turned into an arithmetic the restored
     * deployment can be measured against.
     *
     * <p>The verdicts themselves used to live in this file, as a second judgement beside
     * {@code TenantBundlePlan}'s. They shipped disagreeing with it on {@code legal_hold} and
     * {@code api_usage_daily} and nothing failed, because the platform half of the bundle was
     * hand-written SQL here that never invoked the bundler — so this file could assert that holds travel
     * while the shipped bundler refused to extract a held organization at all. They now live in
     * {@link ExtractionVerdicts}, in the bundler's own vocabulary, and
     * {@code compliance.internal.ExtractionVerdictAgreementTest} fails the build if that mirror ever
     * says something {@code TenantBundlePlan} does not.
     *
     * <p><b>Only three answers exist and each is a different failure when it is wrong.</b> A row that
     * SHOULD have travelled and did not costs the far side a capability with no error at all; a row that
     * should have stayed and travelled hands one tenant's deployment the platform's own record; and
     * {@code WRITTEN_FRESH} is the third, which is neither — the destination writes exactly one of its
     * own, and doing nothing satisfies the other two but not this one, which is how
     * {@code tenant_placement} went unemitted for the whole of Phase 6.
     *
     * @return the exact number of rows naming this organization the restored deployment must hold, or
     *     {@code -1} for "at least one" — {@code SEEDED_FOR_THIS_ORG} carries whatever the origin had
     */
    private static long rowsAllowedOnTheFarSide(ExtractionVerdicts.Verdict verdict) {
        return switch (verdict) {
            case SEEDED_FOR_THIS_ORG -> -1;
            case WRITTEN_FRESH -> 1;
            // Everything else is an absence, for five different reasons that all read the same here:
            // snapshotted whole (no org predicate at all), projected, fresh, rebuilt, re-minted,
            // rewritten, drained, or simply not the tenant's to take.
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ (c)

    /**
     * <strong>ADR 0010 §6 part (c), the one that must not be stubbed: the dump and the restore, actually
     * performed, into a database that has never heard of the platform — and then the tenant served out
     * of the artifact the SHIPPED bundler emits.</strong>
     *
     * <p>The narrative is §6's, step for step. A real organization is placed in a real
     * {@code t_<32hex>} silo and given real rows — two members (one live, one revoked), a role with
     * grants, a ticket with a message, a webhook subscription <em>with its signing secret</em>, a
     * subscription pointing at a plan across the soft ref V53 cut, and tenant audit rows. A second
     * Postgres gets the platform sequence and nothing else. Then the two routes §6 names, in order:
     *
     * <ol>
     *   <li><b>Item 1, taken by hand.</b> {@code pg_dump -n t_<hex>} takes the schema whole and
     *       {@code psql --set=ON_ERROR_STOP=1} replays it. That is literally the runbook step, and what
     *       it proves is that one tenant schema is a restorable unit on its own: every table, every row,
     *       every constraint, and not one reference out of it.</li>
     *   <li><b>The bundle, as shipped.</b> The hand-restored schema is then <em>dropped</em> and rebuilt
     *       by the tenant migration sequence, because that is what the bundle's own header requires of a
     *       destination ("<em>the destination must already have run BOTH migration sequences</em>") and
     *       why: a dumped schema arrives carrying the SOURCE's {@code flyway_schema_history}, which is
     *       another schema's applied-version record wearing this one's name. Into that,
     *       {@code TenantBundler}'s emitted script — all eleven parts, the tenant's own rows included —
     *       and every serve-side assertion below then runs on top of what the bundler actually
     *       produced.</li>
     * </ol>
     *
     * <p><b>Why both, rather than the second alone.</b> They fail differently and neither implies the
     * other. Route 1 is the only thing that can say a {@code pg_dump} of this schema is restorable at
     * all — that is the physical form of the cross-tier foreign-key rule, and it is the step an operator
     * takes. Route 2 is the only thing that can say the shipped bundler carries what it claims: the row
     * counts on the far side are compared against the ORIGIN both times, so route 1 measures
     * {@code pg_dump} and route 2 measures {@code TenantExtractionService} and {@code TenantBundlePlan}.
     *
     * <p><b>{@code ON_ERROR_STOP=1} is the assertion, not a tidiness flag.</b> Without it {@code psql}
     * prints the failure of a dangling foreign key and exits zero, and this test would report a
     * successful restore of a broken tenant — which is precisely the outcome it exists to make
     * impossible. It applies to both artifacts, and the bundle wraps itself in one transaction on top,
     * so a failure anywhere in it leaves the destination empty rather than half a tenant.
     *
     * <p><b>The strongest thing arranged here is an absence.</b> The extracted deployment's
     * {@code tenant_pool} holds no tables at all — only the platform sequence ran, and the tenant
     * sequence is pointed at the silo alone. So there is no second tenant home for an unqualified name
     * to fall through to: if the tenant can be served, it is being served out of the one schema this
     * test filled, and nothing else. It also means the bundle is restored into a SILO, which is the
     * shape every extraction has had since {@code 0822943} made {@code silo-per-org} the default, and
     * the shape {@code TenantBundleTest} cannot exercise — it restores into {@code tenant_pool}, where
     * the script's default and the truth coincide.
     */
    @Test
    void aDumpedTenantRestoresWholeAndTheShippedBundlersArtifactThenServesIt() {
        UUID member = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        UUID revoked = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        String silo = silos.place(orgId);

        UUID memberRole = seedTheTenant(silo, orgId, member, revoked);
        seedThePlatformRowsThatNameThisTenant(orgId, member);
        // Not decoration: without it every assertion below could be satisfied by rows that never left
        // tenant_pool, and the dump would be of an empty schema that restores perfectly.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, orgId, "membership", "person_id", member);
        Map<String, Long> atTheOrigin = rowCountsIn(jdbc, silo);

        // ---- route 1: item 1 by hand, exactly as docs/runbooks/tenant-extraction.md spells it --------
        String dump = dumpTenantSchema(silo);
        assertThat(dump)
                .describedAs("pg_dump -n must carry the schema itself, or the restore lands the tenant's"
                        + " tables wherever the restoring session's search_path happens to point")
                .contains("CREATE SCHEMA " + silo);

        buildTheExtractedDeploymentsPlatformSchema();
        restoreInto(dump, DUMP_PATH, "the hand-run pg_dump of " + silo);
        assertTheRestoredSchemaIsTheWholeTenantAndNothingElse(axisOn(silo), silo, atTheOrigin);

        // ---- route 2: the shipped bundler's own artifact ---------------------------------------------
        TenantBundleArtifact bundle = theShippedBundlersArtifact(orgId);
        discardTheHandRestoredSchema(silo);
        buildTheExtractedDeploymentsTenantSchema(silo);
        restoreInto(pointTheRestoreAtTheSilo(bundle.script(), silo), BUNDLE_PATH,
                "the bundle TenantBundler emitted for " + orgId);

        JdbcTemplate extractedPlatform = axisOn(TenantSchemas.PLATFORM);
        JdbcTemplate extractedTenant = axisOn(silo);

        assertTheRestoredSchemaIsTheWholeTenantAndNothingElse(
                extractedTenant, silo, everyTableButTheMigrationHistory(atTheOrigin));
        assertTheFarSidesMigrationHistoryIsItsOwn(extractedTenant, silo, bundle);
        assertTheBundleCarriedThePlatformRowsThisTenantIsNamedIn(
                extractedPlatform, extractedTenant, silo, orgId, member);
        assertTheExtractedTenantAuthorizesItsMemberAndStillRefusesTheRevokedOne(
                extractedTenant, orgId, member, revoked, memberRole);
        assertTheExtractedTenantOpensATicketAndExportsItselfFromItsOwnSchemaAlone(
                extractedTenant, silo, orgId, member);
        assertTheExtractedDeploymentInheritedNoInfrastructureAndNoLoginLink(
                extractedPlatform, silo, orgId);
        assertTheNightlyPurgeRunsUnheldOnTheFarSideWhichIsWhyAHeldOrgIsNotBundled(
                extractedTenant, silo, orgId, member);
    }

    // ------------------------------------------------------------------ (c) — the claims, one each

    /**
     * The restored schema is the tenant tier, every row of it arrived, and no constraint in it points
     * anywhere else. Part (a) and part (b) re-asked on the far side, which is where they stop being
     * statements about a document and become statements about a deployment.
     *
     * <p><b>Run once per route, and the expected counts are what differ.</b> After the hand-run
     * {@code pg_dump} the caller passes the origin's counts whole, {@code flyway_schema_history}
     * included — a physical restore of a schema carries the record of the migrations that BUILT that
     * schema, which on that side is simply true. After the bundle it passes them without that table,
     * because {@code TenantExtractionService} deliberately excludes it: the destination schema has a
     * history of its own, written by the Flyway pass that made it, and copying the source's would
     * overwrite a true record with another schema's. {@link #assertTheFarSidesMigrationHistoryIsItsOwn}
     * is where that exclusion stops being an omission and becomes a claim.
     *
     * @param expected table name to row count that the restored schema must match exactly. The
     *     comparison is restricted to these tables and no others, which is safe only because the table
     *     SET is asserted against {@code docs/DATA_MODEL.md} on the line above — without that, an
     *     unexpected table on the far side could hide in the gap.
     */
    private void assertTheRestoredSchemaIsTheWholeTenantAndNothingElse(
            JdbcTemplate extracted, String silo, Map<String, Long> expected) {
        Set<String> restored = baseTablesIn(extracted, silo);
        assertThat(restored)
                .describedAs("the restored %s must be exactly the tenant tier %s names", silo, DATA_MODEL)
                .isEqualTo(tenantTierTables());

        Crossings crossings = foreignKeyCrossings(extracted, List.of(silo), tiers());
        assertThat(crossings.schemasThatDeclaredKeys())
                .describedAs("the restored tenant must carry its own intra-tenant foreign keys; a restore"
                        + " that silently dropped every constraint would satisfy the next assertion")
                .containsExactly(silo);
        assertThat(crossings.leavingTheirSchema())
                .describedAs("a foreign key out of the restored schema cannot resolve here — the table it"
                        + " names is in a database this deployment has no route to")
                .isEmpty();
        assertThat(crossings.joiningTwoTiers()).isEmpty();
        assertThat(extracted.queryForList(
                "select conname from pg_constraint con"
                        + " join pg_namespace n on n.oid = con.connamespace"
                        + " where n.nspname = ? and con.contype = 'f' and not con.convalidated",
                String.class, silo))
                .describedAs("a NOT VALID foreign key would mean the restore admitted rows the constraint"
                        + " does not actually hold for")
                .isEmpty();

        assertThat(expected.values())
                .describedAs("the fixture must seed rows, or every count below is trivially equal")
                .anyMatch(rows -> rows > 0);
        Map<String, Long> counted = rowCountsIn(extracted, silo);
        counted.keySet().retainAll(expected.keySet());
        assertThat(counted)
                .describedAs("every row of every table, on the other side")
                .isEqualTo(expected);
    }

    /**
     * <strong>The far side's schema came from the migration set, and the bundle carried no part of the
     * source's record of it.</strong> {@code TenantBundler}'s own class note makes this one of the four
     * things it says are load-bearing and not obvious — "<em>a dumped schema arrives carrying the
     * SOURCE's {@code flyway_schema_history}, which is another schema's applied-version record wearing
     * this one's name</em>" — and until the artifact itself was under test there was nothing anywhere
     * that would have noticed the day it did.
     *
     * <p>Two halves, and the script assertion is the sharper one. That the destination's history is
     * non-empty says its own Flyway pass ran; that the emitted SQL never names the table at all says the
     * exclusion is a property of what the bundler produces rather than of what this restore happened to
     * collide with. A carried history would not fail loudly — the rows have their own primary key — it
     * would leave the destination believing it had applied migrations it never ran, and the next
     * {@code TenantMigrationRunner} pass would skip exactly those.
     */
    private void assertTheFarSidesMigrationHistoryIsItsOwn(
            JdbcTemplate extracted, String silo, TenantBundleArtifact bundle) {
        // The STATEMENT, not the word: BundleScriptWriter's preamble explains in a comment why the
        // schema half comes from the migration set, and that comment names the table. Asserting on the
        // bare name would fail on the explanation instead of on the behaviour, which is the kind of
        // assertion that gets weakened rather than read.
        assertThat(bundle.script())
                .describedAs("the bundle must not carry one row of the source's %s — the destination's"
                        + " schema is built by the migration set and writes its own", FLYWAY_HISTORY)
                .doesNotContain("insert into \"" + FLYWAY_HISTORY + "\"");
        assertThat(count(extracted, "select count(*) from " + TenantTierTables.quote(silo) + "."
                + TenantTierTables.quote(FLYWAY_HISTORY)))
                .describedAs("the restored tenant schema must hold the destination's OWN migration"
                        + " history, which is what proves it was built by the tenant sequence rather"
                        + " than left over from the hand dump this route replaced")
                .isPositive();
    }

    /**
     * <strong>§6 bundle items 2, 3, 4 and 5 arrived, and the bundler is what put them there.</strong>
     * This method is what the ninety lines of hand-written {@code copyRows(...)} became: the copying is
     * the shipped artifact's, so what is left here is the checking.
     *
     * <p><b>The org-scoped platform tables are checked by DERIVING the arithmetic from the judgement,
     * every table of it, rather than by naming the interesting ones.</b> {@link ExtractionVerdicts} is
     * the promotion-side mirror of {@code TenantBundlePlan} and cannot disagree with it
     * ({@code ExtractionVerdictAgreementTest}), and the enumeration test above makes the catalogue
     * unable to grow an org-scoped platform table the mirror does not answer for. So this loop covers
     * the whole judgement by construction: a table added in V60 with an {@code org_id} is asserted here
     * on the day its verdict is written, with no edit to this file. Twelve tables today — one
     * {@code SEEDED_FOR_THIS_ORG} that must arrive, one {@code WRITTEN_FRESH} that must be exactly the
     * destination's own, and ten absences for eight different reasons.
     *
     * <p>The origin deliberately seeds four of them so the zeros are the absence of rows that <em>really
     * existed</em>: a released legal hold, a day of metered usage, an API key and the membership index
     * row {@code EdgeSeed.member} writes. Compare that with what a vacuous pass looks like — every count
     * zero because nobody ever wrote one — which is the state this loop was in before those rows were
     * seeded, and which is indistinguishable from a bundle that carries nothing at all.
     *
     * <p><b>The person projection is checked against the tenant's own rows, not against a list.</b>
     * Every {@code *_person_id}, {@code created_by} and {@code updated_by} in the restored schema is read
     * back out of the catalogue and each one must resolve to a {@code platform.person} row on this side —
     * so a human the tenant names anywhere and the projector missed fails here, and a column added in
     * V60 needs nothing added to this file. Only {@code person} travels: {@code external_identity},
     * verified {@code person_contact} rows, preferences and profiles all stay, because copying either of
     * the first two would put two systems in violation of a global uniqueness constraint (§2.2), and
     * that half is asserted in {@link #assertTheExtractedDeploymentInheritedNoInfrastructureAndNoLoginLink}.
     *
     * <p>The catalogue snapshot is asserted through the one join it exists for. §6 item 4's real symptom
     * is not an empty {@code plan} table — a booting deployment's {@code PlanSeeder} would fill one — it
     * is a re-seeded catalogue holding the same plans under NEW ids, so the tenant's
     * {@code org_subscription.plan_id} matches nothing, {@code EntitlementResolver} falls back to
     * {@code findByCode("FREE")} and an ENTERPRISE tenant is silently downgraded. The join across V53's
     * soft ref is the property, and nothing in the database enforces it any more.
     */
    private void assertTheBundleCarriedThePlatformRowsThisTenantIsNamedIn(
            JdbcTemplate extractedPlatform, JdbcTemplate extractedTenant, String silo, UUID orgId,
            UUID member) {
        // Item 2, the routing rows. One organization and only one: an extracted deployment that
        // inherited the platform's registry would be serving 5,000 tenants it does not have.
        assertThat(count(extractedPlatform, "select count(*) from platform.organization"))
                .describedAs("one tenant, its own (ADR 0010 §6 item 2)")
                .isEqualTo(1);
        assertThat(count(extractedPlatform,
                "select count(*) from platform.organization where id = ?", orgId))
                .isEqualTo(1);
        assertThat(count(extractedPlatform,
                "select count(*) from platform.external_organization where organization_id = ?", orgId))
                .describedAs("the JWT's organization claim resolves HERE before any tenant is known"
                        + " (§2), so a deployment without this row cannot authenticate anybody")
                .isEqualTo(1);

        assertThat(ExtractionVerdicts.all())
                .describedAs("an empty judgement would make the loop below assert nothing while reading"
                        + " exactly like it asserts everything")
                .isNotEmpty();
        ExtractionVerdicts.all().forEach((table, verdict) -> {
            long held = count(extractedPlatform,
                    "select count(*) from platform." + table + " where org_id = ?", orgId);
            long allowed = rowsAllowedOnTheFarSide(verdict);
            String why = "platform." + table + " is " + verdict + ", so the extracted deployment must"
                    + " hold " + (allowed < 0 ? "at least one" : allowed) + " of this organization's"
                    + " rows and holds " + held + ". legal_hold is the sharp one: a hold is custody"
                    + " rather than data, which is why TenantBundler REFUSES to bundle an organization"
                    + " under a live one instead of copying it — see the purge assertion below for what"
                    + " the far side does unheld. tenant_placement is the other: WRITTEN_FRESH is"
                    + " satisfied by doing nothing under every other verdict and by exactly one row"
                    + " here, and a missing one routes the whole tenant to an empty tenant_pool in"
                    + " silence (ADR 0010 §1)";
            if (allowed < 0) {
                assertThat(held).describedAs(why).isPositive();
            } else {
                assertThat(held).describedAs(why).isEqualTo(allowed);
            }
        });

        // Item 3, and the derivation is the assertion: ask the restored schema which humans it names.
        Set<UUID> named = peopleNamedIn(extractedTenant, silo);
        assertThat(named)
                .describedAs("the projection must be checked against the tenant's own rows, and this"
                        + " tenant names people in several tables")
                .isNotEmpty()
                .contains(member);
        assertThat(new LinkedHashSet<>(extractedPlatform.queryForList(
                "select id from platform.person", UUID.class)))
                .describedAs("every human the restored tenant refers to must resolve to a person row on"
                        + " this side — one that does not is a name the far side renders as a bare uuid,"
                        + " on the one row an auditor asked about (§6 item 3)")
                .containsAll(named);

        // Item 4, through the one join it exists for.
        assertThat(count(extractedTenant, "select count(*) from "
                + TenantTierTables.quote(silo) + ".org_subscription s"
                + " join platform.plan p on p.id = s.plan_id where s.org_id = ?", orgId))
                .describedAs("the tenant's subscription must resolve to a plan the bundle carried, with"
                        + " the id it named. V53 cut this foreign key, so nothing in the database will"
                        + " catch it: the failure is EntitlementResolver falling back to FREE and an"
                        + " ENTERPRISE tenant being served the free entitlements with no error anywhere")
                .isEqualTo(1);
    }

    /**
     * <strong>The AGENTS §5.5 invariant, re-proved on the far side of an extraction: a revoked membership
     * denies.</strong> The whole authorization walk — {@code membership → org_role → role_permission} —
     * resolves inside the restored schema with the platform holding nothing but the bundle, which is what
     * §2.1 means by "an extracted org that cannot answer 'who is in this org, with what role' without
     * calling home is not extracted".
     *
     * <p>The negative is the half that matters and it is asserted against a role that DOES hold a grant:
     * the revoked member's seat is soft-deleted, their role is not, so an empty permission set can only
     * come from the tombstone having travelled. A test that revoked the role instead would pass with the
     * {@code deleted_at} column missing entirely.
     */
    private void assertTheExtractedTenantAuthorizesItsMemberAndStillRefusesTheRevokedOne(
            JdbcTemplate extracted, UUID orgId, UUID member, UUID revoked, UUID memberRole) {
        assertThat(permissionsOf(extracted, orgId, member))
                .describedAs("the member's permissions must resolve out of the tenant's own schema")
                .containsExactly("member:invite", "org:read");
        assertThat(permissionsOf(extracted, orgId, revoked))
                .describedAs("a soft-deleted membership denies, on this side too — and their role still"
                        + " holds a grant, so an empty set can only be the tombstone (AGENTS §5.5)")
                .isEmpty();

        assertThat(extracted.queryForList(
                "select m.person_id from membership m"
                        + " join org_role r on r.id = m.role_id"
                        + " where m.org_id = ? and m.deleted_at is null and r.deleted_at is null",
                UUID.class, orgId))
                .describedAs("list members: the live seat and only the live seat")
                .containsExactly(member);
        assertThat(extracted.queryForObject(
                "select code from org_role where id = ?", String.class, memberRole))
                .isEqualTo("ADMIN");
        assertThat(extracted.queryForObject(
                "select secret from webhook_subscription where org_id = ?", String.class, orgId))
                .describedAs("the signing secret is the tenant's credential and MUST travel — it is the"
                        + " one line that separates this artifact from GdprBundleService's, whose whole"
                        + " point is that it does not carry it")
                .isEqualTo("sh-extraction-secret");
    }

    /**
     * "Open a ticket, run an export" — the two write-then-read halves of §6's sentence.
     *
     * <p><b>The ticket is the sharper of the two.</b> {@code ticket.first_response_due_at} and
     * {@code resolution_due_at} are NOT NULL and their values come from {@code platform.sla_policy} by way
     * of {@code SupportService.resolveSlaMinutes}, so opening one in an extracted deployment reads the
     * catalog snapshot of §6 item 4. Worth recording precisely, because §6 describes the missing-catalog
     * failure as silent and for this table it is not: without a policy the insert fails on the not-null
     * constraint. (It is silent for entitlements, exactly as §6 says — {@code limitOf} returns null and
     * {@code requireWithinLimit} then does nothing.)
     *
     * <p>The export is driven by the REAL {@link TenantTierTables} planner pointed at the restored
     * schema, not by SQL this test wrote. That is the strongest available statement that the extracted
     * tenant is self-describing: the planner derives its table set from {@code information_schema} and its
     * predicates from {@code pg_constraint}, and it REFUSES to plan a table it cannot route to an
     * organization — so a table whose parent stayed behind fails here rather than exporting short.
     */
    private void assertTheExtractedTenantOpensATicketAndExportsItselfFromItsOwnSchemaAlone(
            JdbcTemplate extracted, String silo, UUID orgId, UUID member) {
        int[] sla = firstSlaPolicy(extracted);
        UUID opened = UUID.randomUUID();
        extracted.update("""
                insert into ticket (id, org_id, opener_person_id, subject, priority, status,
                                    first_response_due_at, resolution_due_at, escalated, version, created_at)
                values (?, ?, ?, 'opened after the extraction', 'P3', 'OPEN',
                        now() + make_interval(mins => ?), now() + make_interval(mins => ?), false, 0, now())
                """, opened, orgId, member, sla[0], sla[1]);
        assertThat(extracted.queryForObject(
                "select subject from ticket where id = ?", String.class, opened))
                .isEqualTo("opened after the extraction");

        TenantTierTables.Plan plan = new TenantTierTables(extracted).planFor(silo);
        assertThat(new TreeSet<>(plan.tableNames()))
                .describedAs("the real planner must cover the whole tenant tier in the restored schema —"
                        + " it refuses to plan a table it cannot route, so a parent left behind fails here")
                .isEqualTo(tenantTierTables());

        Map<String, Long> exported = new TreeMap<>();
        for (TenantTierTables.TenantTable table : plan.tables()) {
            Object[] organization = new Object[table.parameters()];
            Arrays.fill(organization, orgId);
            exported.put(table.name(), count(extracted,
                    "select count(*) from " + TenantTierTables.quote(silo) + "."
                            + TenantTierTables.quote(table.name()) + " " + TenantTierTables.ROW_ALIAS
                            + " where " + table.predicate(), organization));
        }
        assertThat(exported)
                .describedAs("the export reaches the child tables through their parents' foreign keys —"
                        + " ticket_message and role_permission have no org_id of their own, so a plan that"
                        + " only understood `org_id = ?` would report them empty. `ticket` is three"
                        + " because the export runs after the ticket opened above, which is also the proof"
                        + " that a write made on this side is part of what the tenant now owns")
                .containsEntry("ticket", 3L)
                .containsEntry("ticket_message", 2L)
                .containsEntry("role_permission", 3L)
                .containsEntry("membership", 2L);
    }

    /**
     * §6 item 6, and the two rows that must NOT have come with the tenant.
     *
     * <p><b>Infrastructure is fresh and empty.</b> A copied {@code shedlock} hands the new deployment a
     * future {@code lock_until} and {@code @SchedulerLock} then skips every job without logging anything;
     * a copied {@code event_publication} replays events the platform already delivered — duplicate
     * webhooks, duplicate notifications, duplicate indexing. Both are silent, which is why they are
     * asserted rather than assumed.
     *
     * <p><b>{@code external_identity} is empty on purpose and the tenant still authorizes.</b> §2.2
     * forbids copying it — {@code uq_external_identity_subject_live} is one-login-one-human platform-wide
     * and two systems holding the same link is a violation neither can see — so §6 item 9 makes the
     * extracted deployment re-link its members against its own issuer. That this test can prove
     * authorization while that table is empty is the practical form of AGENTS §5.2's rule: everything
     * durable keys on {@code person.id}, never on the token subject.
     */
    private void assertTheExtractedDeploymentInheritedNoInfrastructureAndNoLoginLink(
            JdbcTemplate extractedPlatform, String silo, UUID orgId) {
        for (String infrastructure : List.of("event_publication", "event_inbox", "shedlock",
                "idempotency_key", "queue_signal", "tenant_freeze")) {
            assertThat(count(extractedPlatform, "select count(*) from platform." + infrastructure))
                    .describedAs("%s is fresh and empty in an extracted deployment; a copy of it is an"
                            + " outage with no error message (ADR 0010 §6 item 6)", infrastructure)
                    .isZero();
        }
        assertThat(count(extractedPlatform, "select count(*) from platform.external_identity"))
                .describedAs("the login links deliberately did NOT travel (§2.2, §6 item 9), and the"
                        + " authorization assertions above passed anyway — which is what keying on"
                        + " person.id rather than on the token subject buys (AGENTS §5.2)")
                .isZero();
        assertThat(count(extractedPlatform,
                "select count(*) from platform.tenant_placement where org_id = ?", orgId))
                .describedAs("the extracted deployment writes its own placement — exactly one, its own")
                .isEqualTo(1);
        assertThat(count(extractedPlatform, "select count(*) from platform.tenant_placement"))
                .describedAs("and ONLY its own: a deployment that inherited the platform's registry"
                        + " would be leasing 5,000 schemas it does not have")
                .isEqualTo(1);

        // The placement row names the schema the tenant's rows actually landed in, and this is the
        // assertion the whole editable-search_path design rests on. BundleScriptWriter derives
        // schema_name from current_schema() rather than spelling `tenant_pool` a second time, precisely
        // so that editing the one line in the preamble moves the registry row with it — and this restore
        // edited that line, to a t_<32hex> silo. TenantBundleTest cannot make this claim: it restores
        // into tenant_pool, where the script's default and the truth are the same string and a hard-coded
        // name would pass. A placement row disagreeing with the search_path is ADR 0010 §1's worst
        // outcome with one extra step in front of it.
        assertThat(extractedPlatform.queryForObject(
                "select schema_name || '/' || state from platform.tenant_placement where org_id = ?",
                String.class, orgId))
                .describedAs("the extracted deployment must be able to ROUTE its one tenant, to the"
                        + " schema the restore actually put it in")
                .isEqualTo(silo + "/ACTIVE");
    }

    /**
     * "…and let the nightly jobs run once", narrowed to the one statement shape in those jobs that
     * crosses the tier: {@code SoftDeletePurgeJob}'s active-hold anti-join, which names
     * {@code platform.legal_hold} explicitly because there must be exactly one set of holds.
     *
     * <p><b>This is the hazard that justifies a refusal, asserted rather than asserted around.</b> The
     * origin placed a live ORG-scoped hold on this tenant, and
     * {@link #assertTheShippedBundlerRefusesAnOrganizationUnderALiveHold} is where the shipped bundler
     * declined to produce anything at all for it. The hold did not travel — {@link ExtractionVerdicts}
     * says {@code STAYS_ON_THE_PLATFORM}, {@code TenantBundlePlan} says the same, and the bundle
     * restored above is now the thing that decided it — so in the extracted deployment the qualified
     * name resolves to <em>its own</em>, empty {@code legal_hold}, the soft-deleted membership is
     * purgeable by every rule that is left, and the nightly purge would hard-delete data a court said to
     * keep (V34's header, {@code SoftDeletePurgeJob}'s javadoc, ADR 0010 §5.3). That is not a bug in the
     * bundle; it is the reason {@code TenantBundler} refuses to produce one for an organization under a
     * live hold at all, and it is why the refusal escalates rather than suggesting the hold be released.
     *
     * <p>An earlier version of this method asserted the opposite — that the hold came across and blocked
     * the purge — while the shipped bundler refused the extraction outright. Both were green, because
     * the platform half of the bundle was this file's own SQL and copied the row itself. The negative
     * control is kept and inverted: writing a hold into the extracted deployment's OWN table makes the
     * same row unpurgeable, which is what proves the count above is the missing hold and not an empty
     * {@code membership}.
     */
    private void assertTheNightlyPurgeRunsUnheldOnTheFarSideWhichIsWhyAHeldOrgIsNotBundled(
            JdbcTemplate extracted, String silo, UUID orgId, UUID member) {
        String purgeable = "select count(*) from " + TenantTierTables.quote(silo) + ".membership m"
                + " where m.deleted_at is not null"
                + " and not exists (select 1 from platform.legal_hold h"
                + "                   where h.released_at is null and h.org_id = m.org_id)";
        assertThat(count(extracted, purgeable))
                .describedAs("the origin's live hold did NOT travel, so the extracted deployment's purge"
                        + " sees nothing holding this row back. That is exactly the outcome TenantBundler"
                        + " refuses to create: a tenant extracted out from under a hold resumes"
                        + " hard-deleting data a court said to keep, and says nothing about it (V34,"
                        + " SoftDeletePurgeJob's javadoc)")
                .isEqualTo(1);

        extracted.update("""
                insert into platform.legal_hold (id, scope, org_id, reason, placed_by_person_id, placed_at)
                values (?, 'ORG', ?, 'placed in the extracted deployment', ?, now())
                """, UUID.randomUUID(), orgId, member);
        assertThat(count(extracted, purgeable))
                .describedAs("a hold placed in the extracted deployment's OWN table stops the same row"
                        + " being purgeable — which proves the count above was the absent hold and not an"
                        + " empty membership table, and that the anti-join really does resolve to the far"
                        + " side's legal_hold")
                .isZero();
    }

    // ------------------------------------------------------------------ the origin fixture

    /**
     * The tenant's own rows, written into the silo BY NAME. Qualified rather than routed for the reason
     * {@code TenantPromotionTest} gives: a fixture that seeded through the router would be asking the
     * router where the rows are and then asking it again, and both answers would agree even if the router
     * were reverted.
     *
     * <p>{@code EdgeSeed.member} is the exception and it routes on purpose — it also writes the
     * {@code platform.org_membership_index} row in the same call, which is what production does in the
     * same transaction, and open-coding that here would be a second copy of the rule.
     *
     * @return the live member's role id
     */
    private UUID seedTheTenant(String silo, UUID orgId, UUID member, UUID revoked) {
        String tenant = TenantTierTables.quote(silo);
        UUID memberRole = EdgeSeed.member(jdbc, orgId, member, "ADMIN");
        UUID revokedRole = EdgeSeed.member(jdbc, orgId, revoked, "SUPPORT");
        jdbc.update("insert into " + tenant + ".role_permission (role_id, permission) values (?, 'org:read')",
                memberRole);
        jdbc.update("insert into " + tenant
                + ".role_permission (role_id, permission) values (?, 'member:invite')", memberRole);
        // The revoked member's ROLE keeps a grant. Their empty permission set then proves the membership
        // tombstone travelled, rather than proving the role was empty on both sides.
        jdbc.update("insert into " + tenant + ".role_permission (role_id, permission) values (?, 'org:read')",
                revokedRole);
        jdbc.update("update " + tenant + ".membership set deleted_at = now(), version = version + 1"
                + " where org_id = ? and person_id = ?", orgId, revoked);

        UUID ticket = UUID.randomUUID();
        for (UUID id : new UUID[] {ticket, UUID.randomUUID()}) {
            jdbc.update("insert into " + tenant + ".ticket (id, org_id, opener_person_id, subject,"
                    + " priority, status, first_response_due_at, resolution_due_at, escalated, version,"
                    + " created_at) values (?, ?, ?, ?, 'P3', 'OPEN', now() + interval '8 hours',"
                    + " now() + interval '3 days', false, 0, now())", id, orgId, member, "ticket " + id);
        }
        for (String body : new String[] {"opened", "and answered"}) {
            jdbc.update("insert into " + tenant + ".ticket_message (id, ticket_id, author_person_id, body,"
                    + " internal, created_at) values (?, ?, ?, ?, false, now())",
                    UUID.randomUUID(), ticket, member, body);
        }
        jdbc.update("insert into " + tenant + ".webhook_subscription (id, org_id, url, secret, event_types,"
                + " status, version, created_at) values (?, ?, 'https://example.test/hook',"
                + " 'sh-extraction-secret', 'ticket.opened', 'ACTIVE', 0, now())", UUID.randomUUID(), orgId);
        // The soft ref V53 cut out of org_subscription.plan_id -> plan. It resolves here only because the
        // catalog snapshot is bundle item 4; there is no constraint left to notice if it does not.
        jdbc.update("insert into " + tenant + ".org_subscription (id, org_id, plan_id, status, version,"
                + " created_at) values (?, ?, ?, 'ACTIVE', 0, now())",
                UUID.randomUUID(), orgId, anyPlan());
        jdbc.update("insert into " + tenant + ".audit_log (id, org_id, action, occurred_at, version,"
                + " created_at) values (?, ?, 'organization.created', now(), 0, now())",
                UUID.randomUUID(), orgId);
        return memberRole;
    }

    /**
     * The platform-side rows that name this tenant: an active ORG-scoped legal hold, an impersonation
     * session (§6 item 5), a day of metered usage and a live API key. All four are platform-tier and
     * {@link ExtractionVerdicts} gives them four different answers, which is why all four are seeded —
     * the impersonation session {@code SEEDED_FOR_THIS_ORG} so its arrival can be asserted, and the hold
     * ({@code STAYS_ON_THE_PLATFORM}), the usage day ({@code STAYS_ON_THE_PLATFORM}) and the key
     * ({@code REMINTED}) so their <em>non</em>-arrival is the absence of a row that really existed rather
     * than of one nobody wrote. A zero that was always going to be zero proves nothing about a bundle.
     *
     * <p>The {@code exported = false} on the usage row is the argument for its verdict, not a filler
     * value: that column is {@code UsageExportJob}'s half of the double-billing guard, so a carried row
     * would have the far side re-price a day the platform has already invoiced.
     *
     * <p>The key is seeded but never revoked here: this rehearsal takes the bundle in
     * {@code REHEARSAL} mode, which counts what a cutover would revoke and revokes nothing (§6 item 8).
     * What it demonstrates on the far side is that the {@code secret_hash} does not travel in either
     * mode — a carried one would leave a single credential valid against two systems, which is the line
     * between this artifact and {@code GdprBundleService}'s read from the other direction.
     */
    private void seedThePlatformRowsThatNameThisTenant(UUID orgId, UUID member) {
        jdbc.update("""
                insert into platform.legal_hold (id, scope, org_id, reason, placed_by_person_id, placed_at)
                values (?, 'ORG', ?, 'litigation hold for the extraction rehearsal', ?, now())
                """, UUID.randomUUID(), orgId, member);
        jdbc.update("""
                insert into platform.impersonation_session (id, actor_person_id, target_person_id,
                        target_display, org_id, reason, mode, started_at, expires_at, version, created_at)
                values (?, ?, ?, 'the member', ?, 'investigating a ticket', 'READ_ONLY', now(),
                        now() + interval '15 minutes', 0, now())
                """, UUID.randomUUID(), member, member, orgId);
        jdbc.update("""
                insert into platform.api_usage_daily (org_id, day, requests, exported)
                values (?, current_date, 42, false)
                """, orgId);
        jdbc.update("""
                insert into platform.api_key (id, org_id, name, prefix, secret_hash, version, created_at)
                values (?, ?, 'the tenant''s machine credential', ?, repeat('a', 64), 0, now())
                """, UUID.randomUUID(), orgId,
                "sk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    /**
     * Any plan in the catalogue. {@code PlanSeeder} runs as an {@code ApplicationRunner} on every boot of
     * this jar — including the extracted deployment's — so which one is irrelevant; what matters is that
     * the id on the tenant's side resolves to a row on the platform's side after the move.
     */
    private UUID anyPlan() {
        return jdbc.queryForObject("select id from platform.plan order by rank limit 1", UUID.class);
    }

    // ------------------------------------------------------------------ the extracted deployment

    /**
     * The extracted deployment's own {@code platform} schema, built by the same runner production builds
     * it with. Only {@code migratePlatform} — never {@code run()} — because the fan-out would migrate
     * {@code tenant_pool} into a second tenant home, and an unqualified name that fell through to it would
     * make this whole test pass without the restore having worked.
     */
    private static void buildTheExtractedDeploymentsPlatformSchema() {
        SchemaResult platform = TenantMigrationRunner
                .fromClasspath(extractedDataSource(null), MIGRATION_WORKERS)
                .migratePlatform(Mode.MIGRATE);
        assertThat(platform.error())
                .describedAs("the extracted deployment's platform schema is built by the real runner; if"
                        + " that fails there is nothing to restore into")
                .isNull();
    }

    /** {@code pg_dump -n t_<hex>}, run inside the origin container exactly as the runbook step does. */
    private static String dumpTenantSchema(String silo) {
        Container.ExecResult dump = exec(POSTGRES, "pg_dump",
                "--username=" + POSTGRES.getUsername(), "--dbname=" + POSTGRES.getDatabaseName(),
                "-n", silo);
        assertThat(dump.getExitCode())
                .describedAs("pg_dump -n %s failed: %s", silo, dump.getStderr())
                .isZero();
        return dump.getStdout();
    }

    /**
     * <strong>The extracted deployment's own tenant schema, built the way the bundle's header requires
     * a destination to build it</strong> — {@code db/migration/tenant} into the schema the script's
     * {@code search_path} names, through the same runner the fleet is migrated by.
     *
     * <p>Only the silo, never {@code run()}: the fan-out would also migrate {@code tenant_pool} into a
     * second tenant home, and an unqualified name that fell through to it would make this whole test
     * pass without the restore having worked. That is the absence the class note calls the strongest
     * thing arranged here, and it is arranged twice — once for each route.
     *
     * <p>The {@code create schema} is separate because it has to be: {@code TenantMigrationRunner} sets
     * {@code createSchemas(false)} on purpose ("<em>a migration runner that created schemas would turn a
     * typo in a placement row into a new, empty, unserved tenant</em>"), and creating the home before
     * migrating it is ADR 0010 §4.3's order — reserve, build, then announce. The announcement here is
     * the placement row, and the BUNDLE writes that.
     */
    private static void buildTheExtractedDeploymentsTenantSchema(String silo) {
        new JdbcTemplate(extractedDataSource(null))
                .execute("create schema " + TenantTierTables.quote(silo));
        TenantMigrationRunner.Manifest fleet = TenantMigrationRunner
                .fromClasspath(extractedDataSource(null), MIGRATION_WORKERS)
                .fanOut(Mode.MIGRATE, List.of(silo));
        assertThat(fleet.ok())
                .describedAs("the extracted deployment's tenant schema is built by the real migration"
                        + " sequence, which is what the bundle's own header requires of a destination."
                        + " The runner said: %s", fleet.report())
                .isTrue();
    }

    /**
     * Drops the hand-restored schema so the bundle can be replayed into a schema built the way the
     * bundle says it must be.
     *
     * <p><b>Dropping it is the point, not cleanup.</b> Route 1 proved that {@code pg_dump -n t_<hex>} of
     * this tenant restores whole; it did NOT prove that the bundle's own contract is satisfiable, and
     * the two produce different schemas — the dumped one arrives carrying the SOURCE's
     * {@code flyway_schema_history}, which {@code TenantBundler}'s class note names as the reason the
     * schema half comes from the migration set. Leaving route 1's schema in place and layering the
     * bundle's rows on top of it would have every row collide on its primary key, and "fixing" that by
     * emptying the tables first would leave the destination believing it had applied migrations it never
     * ran.
     */
    private static void discardTheHandRestoredSchema(String silo) {
        new JdbcTemplate(extractedDataSource(null))
                .execute("drop schema " + TenantTierTables.quote(silo) + " cascade");
    }

    /**
     * The restore, for either artifact. {@code ON_ERROR_STOP=1} is what turns a printed error into a
     * failed exit code — the default would replay a script with a dangling constraint, report success,
     * and leave this test asserting against half a tenant. Measured, not assumed: without it psql exits
     * 0 having skipped the failing statement.
     *
     * @param what names the artifact in the failure message, because "the restore failed" is a very
     *     different investigation depending on whether {@code pg_dump} or {@code BundleScriptWriter}
     *     produced the file
     */
    private static void restoreInto(String sql, String path, String what) {
        EXTRACTED.copyFileToContainer(Transferable.of(sql), path);
        Container.ExecResult restore = exec(EXTRACTED, "psql",
                "--username=" + EXTRACTED.getUsername(), "--dbname=" + EXTRACTED.getDatabaseName(),
                "--set=ON_ERROR_STOP=1", "--quiet", "--file=" + path);
        assertThat(restore.getExitCode())
                .describedAs("restoring %s failed — every such failure names a table on the wrong side of"
                        + " the boundary (ADR 0010 §6). psql said: %s", what, restore.getStderr())
                .isZero();
    }

    /**
     * <strong>The artifact under test: whatever {@code compliance.internal.TenantBundler} emits for this
     * organization, through the shipped {@code BundleScriptWriter}, as the SQL file an operator
     * restores.</strong> Nothing in this file writes a single {@code insert} into the extracted
     * deployment's {@code platform} schema any more; the ninety lines that used to are what ADR 0010 §7
     * Phase 6 records as the reason the whole gate would have stayed green with the bundler deleted.
     *
     * <p><b>The live legal hold is taken first, and the refusal it produces is an assertion.</b>
     * {@link #seedThePlatformRowsThatNameThisTenant} placed one on this tenant, so the shipped bundler
     * must decline to produce anything at all — and until the artifact was under test, the two files
     * could and did disagree about that in silence: this one asserted end to end that holds TRAVEL while
     * the bundler refused a held organization outright, and both were green. Releasing the hold
     * afterwards is what an operator does once counsel has said the hold is lawfully gone; the released
     * row stays in the table, which is what makes its non-arrival on the far side the absence of a row
     * that really existed.
     *
     * <p>Reached through {@link TenantBundleArtifact} because {@code TenantBundler} is package-private
     * in {@code compliance.internal} and must stay so — it reads every secret the tenant owns. That
     * seam's javadoc records why it is a test class in the bundler's own package rather than a widened
     * production type.
     */
    private TenantBundleArtifact theShippedBundlersArtifact(UUID orgId) {
        assertTheShippedBundlerRefusesAnOrganizationUnderALiveHold(orgId);
        jdbc.update("update platform.legal_hold set released_at = now()"
                + " where org_id = ? and released_at is null", orgId);

        TenantBundleArtifact bundle = TenantBundleArtifact.take(beans, orgId);
        assertThat(bundle.sourceSchema())
                .describedAs("the bundle must have been taken out of the tenant's own silo; a bundler"
                        + " that read tenant_pool would produce an empty, restorable, wrong artifact")
                .isEqualTo(TenantSchemas.siloSchema(orgId));
        assertThat(bundle.rows())
                .describedAs("a bundle accounting for no rows at all would restore cleanly and serve"
                        + " nothing, and every far-side count below would be asserting zero == zero")
                .isPositive();
        assertThat(bundle.doesNotTravel())
                .describedAs("the manifest states what stays behind, because that is the half of an"
                        + " extraction an operator gets surprised by (ADR 0010 §6)")
                .isNotEmpty();
        return bundle;
    }

    /**
     * A hold is custody rather than data. It cannot travel — there must be exactly ONE set of holds
     * (§2) — and an organization extracted out from under one lands in a deployment whose
     * {@code SoftDeletePurgeJob} can see no hold at all, which is
     * {@link #assertTheNightlyPurgeRunsUnheldOnTheFarSideWhichIsWhyAHeldOrgIsNotBundled}'s subject. So
     * the bundle declines to exist, and the sentence it declines with is part of the fix: an earlier
     * version told the operator to "release the hold, or do not extract this tenant", which is advice
     * this code has no business giving. Both halves are asserted — that it refuses, and that it refuses
     * by naming the extraction as the thing to stop.
     */
    private void assertTheShippedBundlerRefusesAnOrganizationUnderALiveHold(UUID orgId) {
        assertThat(count(jdbc,
                "select count(*) from platform.legal_hold where org_id = ? and released_at is null",
                orgId))
                .describedAs("the fixture must actually hold this organization, or the refusal below is"
                        + " a refusal of nothing")
                .isEqualTo(1);
        assertThatThrownBy(() -> TenantBundleArtifact.take(beans, orgId))
                .describedAs("a live hold must stop the bundle being produced at all")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legal")
                .hasMessageContaining("STOP THE EXTRACTION");
    }

    /**
     * The one line the bundle asks its operator to edit, edited — {@code BundleScriptWriter}'s preamble
     * defaults to {@code tenant_pool} because an extracted deployment serves exactly one tenant and
     * needs no silo, and says so above the line.
     *
     * <p><b>Performing that edit here is what makes the placement row's derivation falsifiable.</b> The
     * script takes {@code tenant_placement.schema_name} from {@code current_schema()} rather than
     * spelling the schema a second time, so that editing this line moves the registry row with it; a
     * restore into {@code tenant_pool} cannot tell that apart from a hard-coded default, and this one
     * restores into a {@code t_<32hex>} silo. Both assertions are guards on the edit itself: a preamble
     * that stopped emitting the line, or emitted two, would otherwise leave this method silently
     * returning the script unchanged and the whole restore addressed to an empty {@code tenant_pool}.
     */
    private static String pointTheRestoreAtTheSilo(String script, String silo) {
        Matcher editable = SEARCH_PATH_LINE.matcher(script);
        int lines = 0;
        while (editable.find()) {
            lines++;
        }
        assertThat(lines)
                .describedAs("the bundle must emit exactly ONE editable search_path line — it is how a"
                        + " destination says where its single tenant lives, and the placement row is"
                        + " derived from it (BundleScriptWriter.preamble, writeThePlacementRow)")
                .isEqualTo(1);
        String edited = editable.reset().replaceAll(Matcher.quoteReplacement(
                "set search_path to " + silo + ", " + TenantSchemas.EXTENSIONS + ";"));
        assertThat(edited)
                .describedAs("after the edit nothing may still address the destination's empty pool")
                .doesNotContain("set search_path to " + TenantSchemas.TENANT_POOL)
                .contains("set search_path to " + silo + ", " + TenantSchemas.EXTENSIONS + ";");
        return edited;
    }

    /**
     * The origin's counts without Flyway's own bookkeeping — what the BUNDLE is measured against.
     * {@code TenantExtractionService} excludes {@code flyway_schema_history} deliberately and
     * {@link #assertTheFarSidesMigrationHistoryIsItsOwn} is where that exclusion is asserted rather than
     * merely accommodated. The hand-run {@code pg_dump} is measured against the map whole, because a
     * physical restore genuinely does carry it.
     */
    private static Map<String, Long> everyTableButTheMigrationHistory(Map<String, Long> counts) {
        Map<String, Long> without = new TreeMap<>(counts);
        assertThat(without.remove(FLYWAY_HISTORY))
                .describedAs("the origin's counts must include %s, or this subtraction is silently"
                        + " subtracting nothing and the bundle is being measured against the dump's"
                        + " expectations", FLYWAY_HISTORY)
                .isNotNull();
        return without;
    }

    /** Every person id the restored tenant refers to, from the catalogue rather than from a list. */
    private static Set<UUID> peopleNamedIn(JdbcTemplate extracted, String silo) {
        Set<UUID> people = new LinkedHashSet<>();
        for (Map<String, Object> column : extracted.queryForList(
                """
                select table_name, column_name from information_schema.columns
                 where table_schema = ? and data_type = 'uuid'
                   and (column_name like '%person_id' or column_name in ('created_by', 'updated_by'))
                 order by table_name, column_name
                """, silo)) {
            String name = (String) column.get("column_name");
            people.addAll(extracted.queryForList("select distinct " + TenantTierTables.quote(name)
                            + " from " + TenantTierTables.quote(silo) + "."
                            + TenantTierTables.quote((String) column.get("table_name"))
                            + " where " + TenantTierTables.quote(name) + " is not null",
                    UUID.class));
        }
        return people;
    }

    /**
     * A {@link JdbcTemplate} on the extracted deployment, pinned to one {@code search_path} the way
     * {@code TenantRoutingDataSource} pins production's — {@code <schema>, ext}, with no {@code public}
     * behind it. pgjdbc's {@code currentSchema} becomes a {@code search_path} startup parameter, so every
     * borrow arrives already on the axis and there is nothing to leak between them.
     */
    private static JdbcTemplate axisOn(String schema) {
        JdbcTemplate template =
                new JdbcTemplate(extractedDataSource(schema + ", " + TenantSchemas.EXTENSIONS));
        assertThat(template.queryForObject("select current_schema()", String.class))
                .describedAs("the extracted deployment's connections must arrive on %s", schema)
                .isEqualTo(schema);
        return template;
    }

    private static DriverManagerDataSource extractedDataSource(String searchPath) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                EXTRACTED.getJdbcUrl(), EXTRACTED.getUsername(), EXTRACTED.getPassword());
        if (searchPath != null) {
            Properties connection = new Properties();
            connection.setProperty("currentSchema", searchPath);
            dataSource.setConnectionProperties(connection);
        }
        return dataSource;
    }

    private static Container.ExecResult exec(PostgreSQLContainer container, String... command) {
        try {
            return container.execInContainer(command);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not run " + String.join(" ", command), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + String.join(" ", command), interrupted);
        }
    }

    // ------------------------------------------------------------------ shared queries

    /** Foreign keys that leave their schema, and foreign keys that join two tiers. */
    private record Crossings(List<String> leavingTheirSchema, List<String> joiningTwoTiers,
                             Set<String> schemasThatDeclaredKeys) {}

    private static Crossings foreignKeyCrossings(
            JdbcTemplate jdbc, List<String> schemas, Map<String, String> tiers) {
        List<String> leaving = new ArrayList<>();
        List<String> crossing = new ArrayList<>();
        Set<String> declared = new TreeSet<>();
        for (Map<String, Object> key : jdbc.queryForList(
                """
                select con.conname   as constraint_name,
                       childns.nspname  as child_schema,
                       child.relname    as child_table,
                       parentns.nspname as parent_schema,
                       parent.relname   as parent_table
                  from pg_constraint con
                  join pg_class child on child.oid = con.conrelid
                  join pg_namespace childns on childns.oid = child.relnamespace
                  join pg_class parent on parent.oid = con.confrelid
                  join pg_namespace parentns on parentns.oid = parent.relnamespace
                 where con.contype = 'f' and childns.nspname in (%s)
                 order by childns.nspname, con.conname
                """.formatted(placeholders(schemas.size())),
                schemas.toArray())) {
            String childSchema = (String) key.get("child_schema");
            String parentSchema = (String) key.get("parent_schema");
            String child = (String) key.get("child_table");
            String parent = (String) key.get("parent_table");
            declared.add(childSchema);
            if (!childSchema.equals(parentSchema)) {
                leaving.add("%s: %s.%s -> %s.%s"
                        .formatted(key.get("constraint_name"), childSchema, child, parentSchema, parent));
                continue;
            }
            String childTier = tierOf(tiers, child);
            String parentTier = tierOf(tiers, parent);
            if (!childTier.equals(parentTier)) {
                crossing.add("%s in %s: %s (%s) -> %s (%s)".formatted(
                        key.get("constraint_name"), childSchema, child, childTier, parent, parentTier));
            }
        }
        return new Crossings(leaving, crossing, declared);
    }

    private static String tierOf(Map<String, String> tiers, String table) {
        String tier = tiers.get(table);
        assertThat(tier)
                .describedAs("no '**Tier:**' line for %s in %s — a table with no recorded tier cannot be"
                        + " checked against one (ADR 0010 §2)", table, DATA_MODEL)
                .isNotNull();
        return tier;
    }

    private static Set<String> baseTablesIn(JdbcTemplate jdbc, String schema) {
        return new TreeSet<>(jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = ? and table_type = 'BASE TABLE' and table_name <> ?",
                String.class, schema, FLYWAY_HISTORY));
    }

    private static List<String> siloSchemas(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "select schema_name from information_schema.schemata where schema_name ~ ? order by 1",
                String.class, SILO_SCHEMA_PATTERN);
    }

    /** Every table in the schema with its row count, {@code flyway_schema_history} included. */
    private static Map<String, Long> rowCountsIn(JdbcTemplate jdbc, String schema) {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = ? and table_type = 'BASE TABLE'",
                String.class, schema)) {
            counts.put(table, count(jdbc, "select count(*) from " + TenantTierTables.quote(schema) + "."
                    + TenantTierTables.quote(table)));
        }
        return counts;
    }

    /** The permission set the edge would fill {@code CurrentUser.permissions()} from, in SQL. */
    private static List<String> permissionsOf(JdbcTemplate jdbc, UUID orgId, UUID personId) {
        return jdbc.queryForList("""
                select p.permission
                  from membership m
                  join org_role r on r.id = m.role_id
                  join role_permission p on p.role_id = r.id
                 where m.org_id = ? and m.person_id = ? and m.status = 'ACTIVE'
                   and m.deleted_at is null and r.deleted_at is null
                 order by p.permission
                """, String.class, orgId, personId);
    }

    private static int[] firstSlaPolicy(JdbcTemplate extracted) {
        Map<String, Object> policy = extracted.queryForMap(
                "select first_response_minutes, resolution_minutes from platform.sla_policy"
                        + " where priority = 'P3'");
        return new int[] {(Integer) policy.get("first_response_minutes"),
                (Integer) policy.get("resolution_minutes")};
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long rows = jdbc.queryForObject(sql, Long.class, args);
        return rows == null ? 0 : rows;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    // ------------------------------------------------------------------ docs/DATA_MODEL.md

    /** Every table the tenant schemas must hold: the tenant tier plus the tenant half of the split seven. */
    private static Set<String> tenantTierTables() {
        Set<String> tables = new TreeSet<>(tablesWithTier(TIER_TENANT));
        tables.addAll(tablesWithTier(TIER_SPLIT));
        return tables;
    }

    private static Set<String> tablesWithTier(String tier) {
        Set<String> tables = new TreeSet<>();
        tiers().forEach((table, recorded) -> {
            if (recorded.equals(tier)) {
                tables.add(table);
            }
        });
        return tables;
    }

    /**
     * Table name to tier, straight off the {@code **Tier:**} lines. See the class note on why this test
     * parses the document itself rather than sharing a parser with its two siblings.
     */
    private static Map<String, String> tiers() {
        Map<String, String> tiers = new LinkedHashMap<>();
        String table = null;
        try {
            for (String line : Files.readAllLines(DATA_MODEL, StandardCharsets.UTF_8)) {
                Matcher heading = TABLE_HEADING.matcher(line);
                if (heading.matches()) {
                    table = heading.group(1);
                    continue;
                }
                Matcher tier = TIER_LINE.matcher(line);
                if (tier.matches() && table != null) {
                    tiers.put(table, tier.group(1).split(CLAUSE_SEPARATOR, 2)[0].strip());
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + DATA_MODEL.toAbsolutePath(), failure);
        }
        assertThat(tiers.values())
                .describedAs("tiers parsed out of %s", DATA_MODEL)
                .isNotEmpty()
                .allMatch(KNOWN_TIERS::contains);
        return Map.copyOf(tiers);
    }
}

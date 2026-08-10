package ug.co.smsone.shared.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * <b>The gate for ADR 0010 §6 hop 2→3's non-table half: "Also fresh: the Valkey cache/rate-limit key
 * prefixes and the SeaweedFS bucket root."</b> Phase 6 made the four tables structural — the
 * disposition carries no rows and {@code TenantBundlePlan.PlatformTable.read()} throws — and this is
 * the same move for the two pieces of infrastructure that are not in Postgres.
 *
 * <p><b>Why a shared namespace is worse than a copied {@code shedlock} row.</b> §6's argument for the
 * tables is that a copied lock makes the new deployment run no jobs at all, silently. A shared cache
 * namespace does not stop work; it <em>answers with the wrong installation's data</em> — deployment A's
 * eviction clears B's entry (a hit-rate hole), and A's cached value is served to B's caller, which for
 * {@code org-permissions} is an authorization decision computed against a database B has never seen.
 * A shared bucket is the same shape one layer down, and it has a specific trigger: after a cutover the
 * source still holds the org's soft-deleted {@code document} rows, so {@code SoftDeletePurgeJob}
 * eventually deletes bytes the extracted deployment is serving — from a key it legitimately believes it
 * owns, because {@code organization.id} does not change on extraction and {@code document.storage_key}
 * travels verbatim (§2.2).
 *
 * <p><b>Two gates, because there are two ways to reach shared infrastructure.</b> The enumerations
 * below are decision records in the {@code shared.document.StorageKeyNamespaceTest} tradition: a fifth
 * class that talks to Valkey, or a second class that reads the configured bucket, fails here and its
 * author has to answer one question — <em>which deployment's namespace do these bytes belong to?</em>
 * The behaviour those declarations promise is proved against real containers by
 * {@code shared.cache.ValkeyCacheIntegrationTest}, {@code shared.ratelimit.RateLimitIntegrationTest}
 * and {@code files.internal.DeploymentBucketIntegrationTest}.
 */
class DeploymentNamespaceTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ug.co.smsone");

    private static final String CACHE_PREFIX = "smsone:cache:";
    private static final String CACHE_TOPIC = "smsone:cache:invalidations";

    private static final DeploymentIdentity PLATFORM = new DeploymentIdentity(DeploymentIdentity.PLATFORM);
    private static final DeploymentIdentity EXTRACTED = new DeploymentIdentity("acme");

    /**
     * <b>The default has to be byte-for-byte what this system already writes, and that is a correctness
     * requirement rather than politeness.</b> Renaming the bucket orphans every object already in it —
     * every {@code document.storage_key} keeps pointing at a container nothing addresses, and the
     * symptom is a download that 404s for data that is still there. Renaming the Valkey namespace splits
     * a rolling deploy in two: old pods evict old keys while new pods keep serving what they cached
     * under the new ones, which for {@code org-permissions} is a revoked permission surviving a release.
     *
     * <p>So these five literals are the shipped values, asserted here so that "unchanged" is a test and
     * not a claim in a commit message.
     */
    @Test
    void theDefaultDeploymentWritesExactlyTheKeysAndBucketThisSystemAlreadyWrote() {
        assertThat(new DeploymentIdentity(null).id())
                .as("an absent app.deployment.id is the platform, so an existing deployment upgrades into"
                        + " this change without moving a byte")
                .isEqualTo(DeploymentIdentity.PLATFORM);

        assertThat(PLATFORM.valkeyKey(CACHE_PREFIX)).isEqualTo("smsone:cache:");
        assertThat(PLATFORM.valkeyKey(CACHE_TOPIC)).isEqualTo("smsone:cache:invalidations");
        assertThat(PLATFORM.valkeyKey("rl:write:tenant:0e33")).isEqualTo("rl:write:tenant:0e33");
        assertThat(PLATFORM.valkeyKey("notif:rate:EMAIL")).isEqualTo("notif:rate:EMAIL");
        assertThat(PLATFORM.objectBucket("smsone")).isEqualTo("smsone");
    }

    /**
     * The other side of the same coin. Every namespace moves together off ONE value — that is the whole
     * reason the identity is a name rather than three configurable prefixes: three knobs are three
     * chances to forget one, and forgetting the cache prefix while remembering the bucket looks exactly
     * like a working deployment from the outside.
     */
    @Test
    void everyNamespaceMovesTogetherOffTheOneValue() {
        assertThat(EXTRACTED.valkeyKey(CACHE_PREFIX)).isEqualTo("dep:acme:smsone:cache:");
        assertThat(EXTRACTED.valkeyKey(CACHE_TOPIC)).isEqualTo("dep:acme:smsone:cache:invalidations");
        assertThat(EXTRACTED.valkeyKey("rl:write:tenant:0e33")).isEqualTo("dep:acme:rl:write:tenant:0e33");
        assertThat(EXTRACTED.valkeyKey("notif:rate:EMAIL")).isEqualTo("dep:acme:notif:rate:EMAIL");

        assertThat(EXTRACTED.objectBucket("smsone"))
                .as("a config file copied from the platform, S3_BUCKET and all, still lands somewhere"
                        + " else — sharing a bucket now requires sharing app.deployment.id, which is one"
                        + " visible mistake instead of a silent one")
                .isEqualTo("smsone-dep-acme");
    }

    /**
     * <b>Disjointness is not free and this is where it is bought.</b> The platform contributes NOTHING to
     * its keys (it must — see the byte-for-byte test), so nothing structural separates its namespace from
     * a deployment's unless one side carries a marker the other can never produce. {@code dep:} is that
     * marker, which is why {@code RateLimitProperties} refuses a key prefix that could spell it.
     */
    @Test
    void noDeploymentCanReachAnotherDeploymentsKeysOrBucket() {
        List<String> subsystemKeys = List.of(CACHE_PREFIX, CACHE_TOPIC, "rl:read:ip:127.0.0.1",
                "notif:rate:SMS");
        DeploymentIdentity other = new DeploymentIdentity("acme-eu");

        for (String key : subsystemKeys) {
            assertThat(Set.of(PLATFORM.valkeyKey(key), EXTRACTED.valkeyKey(key), other.valkeyKey(key)))
                    .as("three deployments, one subsystem key '%s': three namespaces", key)
                    .hasSize(3);
            assertThat(PLATFORM.valkeyKey(key))
                    .as("nothing the platform writes may begin with the reserved marker, or a"
                            + " deployment's prefix would be indistinguishable from the platform's own"
                            + " first segment")
                    .doesNotStartWith(DeploymentIdentity.MARKER + ":");
            assertThat(EXTRACTED.valkeyKey(key)).startsWith("dep:acme:");
        }

        assertThat(Set.of(PLATFORM.objectBucket("smsone"), EXTRACTED.objectBucket("smsone"),
                other.objectBucket("smsone"))).hasSize(3);
    }

    /**
     * A malformed id is a startup failure naming itself, never a deployment that boots into somebody
     * else's namespace. The three refusals are not stylistic: {@code ':'} is the Valkey separator (an id
     * carrying one could spell a second deployment's prefix), {@code '_'} and uppercase are illegal in
     * an S3 bucket name and the same id names the bucket, and {@code dep} is the marker itself.
     */
    @Test
    void anIdThatCouldForgeAnotherNamespaceOrBreakABucketNameIsRefused() {
        assertThatIllegalArgumentException()
                .as("':' is the Valkey key separator")
                .isThrownBy(() -> new DeploymentIdentity("acme:eu"))
                .withMessageContaining("app.deployment.id");
        assertThatIllegalArgumentException()
                .as("S3 bucket names have no underscores")
                .isThrownBy(() -> new DeploymentIdentity("acme_eu"));
        assertThatIllegalArgumentException()
                .as("S3 bucket names are lowercase")
                .isThrownBy(() -> new DeploymentIdentity("Acme"));
        assertThatIllegalArgumentException()
                .as("a trailing '-' derives the bucket 'smsone-dep-acme-', which S3 rejects — and it"
                        + " would be rejected on the first upload rather than at startup")
                .isThrownBy(() -> new DeploymentIdentity("acme-"));
        assertThatIllegalArgumentException()
                .as("the marker itself would produce dep:dep:… keys that no longer say who wrote them")
                .isThrownBy(() -> new DeploymentIdentity(DeploymentIdentity.MARKER))
                .withMessageContaining("reserved");
    }

    /**
     * <b>The other half of the disjointness argument, and it is enforced in a different class.</b> The
     * platform's rate-limit keys begin with a CONFIGURED segment, so a prefix free to spell its own
     * separators could put this installation's buckets inside a deployment's namespace —
     * {@code key-prefix: dep:acme} and the deployment {@code acme} would then be one bucket, and one
     * tenant's quota would be spent twice. Asserted here rather than beside the property, because the
     * reason is this file's.
     */
    @Test
    void aRateLimitKeyPrefixThatCouldSpellADeploymentSegmentIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rateLimitPrefixed("dep:acme"))
                .withMessageContaining("key-prefix");
        assertThatIllegalArgumentException()
                .as("the marker alone is enough: 'dep' + ':' + a tier id is a deployment's first two"
                        + " segments")
                .isThrownBy(() -> rateLimitPrefixed(DeploymentIdentity.MARKER));

        assertThat(rateLimitPrefixed("rl")).isEqualTo("rl");
        assertThat(rateLimitPrefixed(null))
                .as("the shipped default, unchanged")
                .isEqualTo("rl");
    }

    private static String rateLimitPrefixed(String keyPrefix) {
        return new ug.co.smsone.shared.ratelimit.RateLimitProperties(
                keyPrefix, null, null, false, null, null).keyPrefix();
    }

    /**
     * S3 caps a bucket name at 63 characters, and the failure has to land at startup. Left to run, the
     * first {@code putObject} of the first extracted tenant fails — and the repair anybody reaches for
     * under that pressure is "point it at the platform's bucket", which is the collision this whole
     * change exists to prevent, arrived at deliberately.
     */
    @Test
    void aBucketNameTooLongForS3FailsAtStartupAndSaysWhatToShorten() {
        DeploymentIdentity longest = new DeploymentIdentity("a".repeat(31));

        assertThat(longest.objectBucket("smsone")).hasSizeLessThanOrEqualTo(63);
        assertThatIllegalStateException()
                .isThrownBy(() -> longest.objectBucket("a-rather-long-configured-bucket-name-here"))
                .withMessageContaining("app.storage.bucket");
    }

    /**
     * <b>Every class in the application that talks to Valkey, and what it does about the deployment.</b>
     * Four, enumerated from the compiled classes so a fifth shows up by existing rather than by being
     * remembered — and one of the four is an exemption, which is why this table carries reasons instead
     * of just names.
     */
    private static final Map<String, String> VALKEY_CLASSES = valkeyClasses();

    private static Map<String, String> valkeyClasses() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("ug.co.smsone.shared.cache.CacheConfig",
                "namespaces the L2 key prefix and the invalidation topic. On the PREFIX rather than in "
                        + "TwoLevelCache's key format, so a cache added tomorrow inherits the namespace "
                        + "by existing");
        declared.put("ug.co.smsone.shared.cache.CacheInvalidationBroadcaster",
                "publishes on this deployment's topic only — a shared one would have one deployment's "
                        + "cache churn dropping another's L1 entries forever");
        declared.put("ug.co.smsone.shared.ratelimit.DistributedRateLimiter",
                "the only class that addresses a Bucket4j bucket at all, which is why it namespaces the "
                        + "key here instead of in the two callers that build one");
        declared.put("ug.co.smsone.shared.ratelimit.RateLimitConfig",
                "EXEMPT: builds the Lettuce client and never a key. Listed anyway, because a table of "
                        + "only the compliant classes could not tell a new exemption from an omission");
        return Map.copyOf(declared);
    }

    /** The entries above that legitimately never see a key, and so never need the identity. */
    private static final Set<String> WRITES_NO_KEY =
            Set.of("ug.co.smsone.shared.ratelimit.RateLimitConfig");

    @Test
    void everyClassThatWritesAValkeyKeyHasReadTheDeploymentIdentity() {
        StringBuilder declared = new StringBuilder("a class reaches Valkey (Lettuce, Bucket4j or Spring"
                + " Data Redis) without being declared in DeploymentNamespaceTest.VALKEY_CLASSES. Two"
                + " deployments share one Valkey after ADR 0010 §6 hop 2->3, so decide which namespace"
                + " its keys belong in: pass them through DeploymentIdentity.valkeyKey, or declare the"
                + " class in WRITES_NO_KEY and say why it never names one. Declared today:");
        VALKEY_CLASSES.forEach((type, why) -> declared.append("\n  ").append(type).append(" → ").append(why));

        assertThat(valkeyTouchingClasses())
                .as(declared.toString())
                .containsExactlyInAnyOrderElementsOf(VALKEY_CLASSES.keySet());

        for (String type : VALKEY_CLASSES.keySet()) {
            if (WRITES_NO_KEY.contains(type)) {
                continue;
            }
            assertThat(readsTheDeploymentIdentity(type))
                    .as("%s writes Valkey keys and never reads the deployment identity, so its keys are"
                            + " shared with every other deployment on that Valkey", type)
                    .isTrue();
        }
    }

    private static Set<String> valkeyTouchingClasses() {
        Set<String> found = new TreeSet<>();
        for (JavaClass type : PRODUCTION) {
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getFullName();
                if (target.startsWith("io.lettuce.core.")
                        || target.startsWith("io.github.bucket4j.")
                        || target.startsWith("org.springframework.data.redis.")) {
                    found.add(type.getName());
                }
            }
        }
        // Non-empty is its own assertion: if the importer stopped seeing these classes the enumeration
        // would compare two empty sets and the gate would be gone without a failure anywhere.
        assertThat(found).as("no Valkey-touching class was found at all — the gate is not looking at the"
                + " production classes").isNotEmpty();
        return found;
    }

    private static boolean readsTheDeploymentIdentity(String type) {
        return PRODUCTION.get(type).getDirectDependenciesFromSelf().stream()
                .anyMatch(dependency -> dependency.getTargetClass().isEquivalentTo(DeploymentIdentity.class));
    }

    /**
     * <b>The object half, and it is one class rather than a list because a bucket is not a key.</b>
     * {@code StorageProperties.bucket()} is the CONFIGURED name; the name this deployment actually
     * addresses is {@code DeploymentBucket}'s, derived from it. Any second reader of the raw property is
     * a request addressed to the platform's container from a deployment that is not the platform — which
     * is the collision, arrived at one method call at a time.
     */
    @Test
    void theConfiguredBucketIsReadByExactlyTheClassThatDerivesThisDeploymentsOwn() {
        Set<String> readers = new TreeSet<>();
        for (JavaClass type : PRODUCTION) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                if ("ug.co.smsone.files.internal.StorageProperties".equals(
                                call.getTargetOwner().getFullName())
                        && "bucket".equals(call.getTarget().getName())
                        && !type.getName().equals("ug.co.smsone.files.internal.StorageProperties")) {
                    readers.add(type.getName());
                }
            }
        }

        assertThat(readers)
                .as("only DeploymentBucket may read the CONFIGURED bucket name — everything else names"
                        + " the bucket THIS deployment owns (DeploymentBucket.name()). Two deployments"
                        + " hold byte-identical object keys after an extraction (ADR 0010 §2.2 keeps"
                        + " organization.id, so document.storage_key travels verbatim), so the bucket is"
                        + " the only thing separating them and a request that names the configured one"
                        + " reaches into the other deployment's container.")
                .containsExactly("ug.co.smsone.files.internal.DeploymentBucket");
    }
}

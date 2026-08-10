package ug.co.smsone.shared.deployment;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * <strong>Which deployment this JVM is, and therefore which shared infrastructure it owns.</strong>
 * ADR 0010 §6 hop 2→3, the half that is not a table: "<em>Also fresh: the Valkey cache/rate-limit key
 * prefixes and the SeaweedFS bucket root.</em>"
 *
 * <h2>Why an identity, and not three prefixes</h2>
 *
 * <p>§6 already makes the case for the tables. Two deployments that share a {@code shedlock} row: the
 * new one silently runs no jobs at all. Two that share {@code event_publication}: every event the
 * platform already delivered is delivered again. The same argument applies unchanged to the two pieces
 * of infrastructure that are not in Postgres, and it is <em>worse</em> for the cache, because a copied
 * lock only stops work while a shared cache namespace <b>answers a question with another deployment's
 * data</b>: A's eviction clears B's entry (a hit-rate hole, survivable) and A's cached value is served
 * to B's caller (an authorization decision computed in another installation, which is not).
 *
 * <p>So the deployment gets ONE name and the namespaces are DERIVED from it. Three independently
 * configured prefixes would be three chances to forget one, and forgetting the cache prefix while
 * remembering the bucket is indistinguishable — from the outside, at 03:00 — from a deployment that is
 * working. One knob has one failure mode: two deployments configured with the same name, which is a
 * single, loud, obvious mistake instead of three quiet ones.
 *
 * <h2>{@code platform} is reserved, and its namespace is the historical one</h2>
 *
 * <p>The default is {@link #PLATFORM}, and a platform-identified deployment produces <b>byte-for-byte
 * the keys and the bucket this system already writes</b>. That is not deference to the past; it is the
 * only safe default:
 *
 * <ul>
 *   <li>Renaming the object bucket <em>orphans every object already in it</em>. Every
 *       {@code document.storage_key} row keeps pointing at a key in a bucket nothing addresses any
 *       more, and the symptom is a download that 404s for data that is still there.</li>
 *   <li>Renaming the Valkey namespace during a rolling deploy splits the fleet in two: the old pods
 *       evict old keys, the new pods keep serving the entries they cached under the new ones. For
 *       {@code org-permissions} that is a revoked permission surviving a deploy — the one cache whose
 *       staleness AGENTS §5.5 refuses outright.</li>
 * </ul>
 *
 * <p><b>Disjointness is therefore not free, and this is how it is bought.</b> A deployment that
 * contributes NOTHING to its keys cannot be told apart from another deployment's prefix by
 * construction, so every non-platform deployment's namespace begins with the reserved marker
 * {@value #MARKER}: {@code dep:<id>:…}. A platform key can then only collide with a deployment's if it
 * begins with {@code dep:} — which is why {@code RateLimitProperties} refuses a key prefix of
 * {@value #MARKER} and refuses one containing {@code ':'} at all (a prefix free to spell its own
 * segments could impersonate a deployment). The object bucket carries the same marker for the same
 * reason: {@code <bucket>-dep-<id>}.
 *
 * <h2>The bucket is derived from the configured one rather than replacing it</h2>
 *
 * <p>{@code app.storage.bucket} keeps its meaning — an operator names their bucket — and the
 * deployment suffixes it. The property that buys is worth stating: <b>you cannot point two
 * deployments at one bucket by copying a config file.</b> An extracted deployment started from the
 * platform's own {@code S3_BUCKET=smsone} lands in {@code smsone-dep-<id>}, not in {@code smsone}, and
 * the only way back to a shared bucket is to also give it the platform's deployment id — the same
 * single, visible mistake as above.
 *
 * <h2>Why the keys themselves cannot carry the deployment, which is the crux</h2>
 *
 * <p>The obvious alternative — put the deployment into the object KEY, next to the organization — is
 * not available, and the reason is the extraction itself. {@code document.storage_key} travels in the
 * tenant's dump <em>verbatim</em>, and {@code organization.id} deliberately does not change on
 * extraction (ADR 0010 §2.2), so after a cutover <b>both deployments hold rows naming exactly the same
 * key</b>. That is what makes the restore work: {@code OrgObjectPrefixes.covers} still recognises the
 * key on the far side, and nothing has to rewrite a column. It also means no segment of a key can ever
 * separate the two — the separation has to sit ABOVE the key, in the container. A bucket is that
 * container; a root prefix inside one bucket is not, because a root prefix would have to be added to
 * the restored keys, which is the column rewrite the design exists to avoid.
 *
 * <p>The failure this prevents is concrete and it is not hypothetical: after a cutover the platform
 * still holds the org's soft-deleted document rows, and {@code SoftDeletePurgeJob} eventually hard-
 * deletes them — {@code delete doc/o/<orgId>/<docId>/report.pdf}. Sharing a bucket, that call removes
 * bytes the extracted deployment is serving right now, from a key it believes it owns, with no error
 * on either side.
 */
@ConfigurationProperties(prefix = "app.deployment")
public record DeploymentIdentity(@DefaultValue(DeploymentIdentity.PLATFORM) String id) {

    /**
     * The reserved name of the deployment that was here first. Its namespaces are the historical ones —
     * see the class note on why any other default would orphan objects and split a rolling deploy.
     */
    public static final String PLATFORM = "platform";

    /**
     * The first segment of every non-platform deployment's Valkey namespace, and the infix of its
     * bucket name. Reserved: nothing the platform writes may begin with it, which is what makes the two
     * namespaces provably disjoint rather than disjoint by inspection.
     */
    public static final String MARKER = "dep";

    /**
     * One lowercase segment. No {@code ':'} (it is the Valkey separator, and an id free to spell one
     * could forge a second deployment's namespace) and no {@code '_'} or uppercase (S3 bucket names
     * forbid both, and the same id names the bucket). It must also START and END alphanumeric, for the
     * same reason and not for tidiness — {@code acme-} would derive the bucket {@code smsone-dep-acme-},
     * which S3 rejects, and the rejection would land on the first upload rather than at startup.
     * Bounded at 31 characters so {@code <bucket>-dep-<id>} still fits S3's 63-character limit for any
     * reasonable bucket name.
     */
    private static final Pattern VALID_ID = Pattern.compile("^[a-z](?:[a-z0-9-]{0,29}[a-z0-9])?$");

    /** S3's hard limit on a bucket name, and the reason the derived name is checked rather than hoped. */
    private static final int MAX_BUCKET_LENGTH = 63;

    public DeploymentIdentity {
        // The house rule: dangerous configuration fails at startup, not at 04:00. A rejected id here is
        // a deployment that will not boot; an accepted-but-wrong one is a deployment that quietly reads
        // another installation's cache.
        if (id == null || id.isBlank()) {
            id = PLATFORM;
        }
        id = id.trim();
        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("app.deployment.id '" + id + "' is not a valid deployment"
                    + " name. It names this installation's Valkey namespace AND its object bucket, so it"
                    + " must be one lowercase segment matching " + VALID_ID.pattern() + ": no ':' (the"
                    + " Valkey key separator — an id carrying one could spell another deployment's"
                    + " namespace), no '_' and no uppercase (S3 bucket names forbid both), and it must"
                    + " end alphanumeric (a trailing '-' derives a bucket name S3 rejects).");
        }
        if (MARKER.equals(id)) {
            throw new IllegalArgumentException("app.deployment.id may not be '" + MARKER + "': that is the"
                    + " reserved first segment of every non-platform deployment's namespace (dep:<id>:…),"
                    + " and a deployment named after the marker would produce 'dep:dep:…' keys that no"
                    + " longer say which deployment wrote them.");
        }
    }

    /**
     * Whether this is the deployment the namespaces already belong to. The one branch in the whole
     * design, stated once here rather than at each call site — see the class note.
     */
    public boolean isThePlatform() {
        return PLATFORM.equals(id);
    }

    /**
     * The Valkey key, key prefix or pub/sub topic this deployment owns, given the one the subsystem
     * asks for. Every Valkey-facing class in the application routes through here — the cache's L2 key
     * prefix, the cache invalidation topic, and every rate-limit bucket, edge and egress alike. Each of
     * those is a single choke point on purpose, so participating in the namespace is not something a
     * subsystem can forget: {@code DeploymentNamespaceTest} fails the build on a class that reaches
     * Valkey without reading this one.
     *
     * @param key the subsystem's own key or prefix, e.g. {@code smsone:cache:} or
     *     {@code rl:write:tenant:<uuid>}
     */
    public String valkeyKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("a deployment namespaces a key; null is not one");
        }
        return isThePlatform() ? key : MARKER + ":" + id + ":" + key;
    }

    /**
     * The object-store bucket this deployment owns, given the configured one. The platform keeps the
     * configured name exactly; every other deployment gets {@code <configured>-dep-<id>}.
     *
     * @throws IllegalStateException if the derived name would exceed S3's 63-character limit — a
     *     startup failure naming both halves, rather than a {@code putObject} that fails on the first
     *     upload of the first tenant to be extracted
     */
    public String objectBucket(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("a deployment names its bucket after the configured one"
                    + " (app.storage.bucket), and there is none");
        }
        if (isThePlatform()) {
            return configured;
        }
        String derived = configured + "-" + MARKER + "-" + id;
        if (derived.length() > MAX_BUCKET_LENGTH) {
            throw new IllegalStateException("deployment '" + id + "' would serve object storage out of"
                    + " bucket '" + derived + "', which is " + derived.length() + " characters and S3"
                    + " allows " + MAX_BUCKET_LENGTH + ". Shorten app.storage.bucket or app.deployment.id;"
                    + " do not point this deployment at the platform's bucket instead, because the two"
                    + " hold the same object keys by design (ADR 0010 §2.2 keeps organization.id across"
                    + " an extraction, so document.storage_key travels verbatim).");
        }
        return derived;
    }
}

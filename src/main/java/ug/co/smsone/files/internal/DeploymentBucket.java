package ug.co.smsone.files.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.deployment.DeploymentIdentity;

/**
 * <strong>The one bucket this deployment addresses, resolved once.</strong> ADR 0010 §6 hop 2→3's
 * "the SeaweedFS bucket root", and the answer to the question that hop asks: bucket-per-deployment or
 * root-prefix-per-deployment.
 *
 * <h2>Bucket, not root prefix, and the reason is the extraction itself</h2>
 *
 * <p>A root prefix would have to be <em>added</em> to a restored tenant's keys, and there is nowhere to
 * add it. {@code document.storage_key} travels in the tenant's dump verbatim and
 * {@code organization.id} deliberately does not change on extraction (ADR 0010 §2.2), so after a
 * cutover both deployments hold rows naming byte-identical keys — that is precisely what makes the
 * restore a copy rather than a rewrite, what keeps {@code OrgObjectPrefixes.covers} true on the far
 * side, and what {@code compliance.internal.TenantObjectExtractionService} relies on when it refuses a
 * bundle object whose key is not the organization's. Adopting a root prefix would mean rewriting a
 * column on every restore, and a restore that edits keys is a restore that can edit them wrong.
 *
 * <p>So the separation has to sit ABOVE the key, and a bucket is the only thing there. It is also the
 * only one an S3 policy can express: a bucket can be denied to a set of credentials outright, where
 * "this prefix but not that one" is an IAM feature with uneven support across S3-compatible stores
 * (and none at all in the SeaweedFS this template ships for development). A prefix is a naming
 * convention; the failure mode here is a key built wrong, and a naming convention cannot contain that.
 *
 * <h2>What this prevents, concretely</h2>
 *
 * <p>After a cutover the platform still holds the organization's soft-deleted {@code document} rows,
 * and {@code SoftDeletePurgeJob} eventually hard-deletes them — which issues
 * {@code delete doc/o/<orgId>/<docId>/report.pdf} against the store. Sharing one bucket, that call
 * removes bytes the extracted deployment is serving right now, from a key it believes it owns. Neither
 * side errors: the platform deleted a key it had a row for, and the far side's download simply 404s.
 *
 * <h2>Derived from the configured bucket, so a copied config file is safe</h2>
 *
 * <p>{@code app.storage.bucket} keeps its meaning; the deployment suffixes it
 * ({@code <bucket>-dep-<id>}), and the platform's own identity leaves it exactly as configured — so
 * this change moves no existing byte. The property worth having is the other direction: an extracted
 * deployment started from a copy of the platform's environment, {@code S3_BUCKET=smsone} and all,
 * lands in {@code smsone-dep-<id>} rather than in the platform's bucket. Sharing a bucket now requires
 * sharing {@code app.deployment.id}, which is one visible mistake instead of a silent one.
 */
@Component
class DeploymentBucket {

    private static final Logger log = LoggerFactory.getLogger(DeploymentBucket.class);

    private final String name;

    DeploymentBucket(StorageProperties properties, DeploymentIdentity deployment) {
        this.name = deployment.objectBucket(properties.bucket());
        // At INFO on the way up because the far side of an extraction is the reader who needs it and has
        // no other way to see it: a deployment restored with the platform's identity writes into the
        // platform's bucket, over keys that are identical by design, and nothing above this line is
        // malfunctioning.
        log.info("Deployment '{}' serves object storage out of bucket '{}'", deployment.id(), name);
    }

    /** The bucket every request in this module names. Nothing else may read {@code StorageProperties.bucket()}. */
    String name() {
        return name;
    }
}

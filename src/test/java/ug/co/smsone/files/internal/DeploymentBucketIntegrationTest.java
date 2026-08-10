package ug.co.smsone.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import ug.co.smsone.shared.deployment.DeploymentIdentity;
import ug.co.smsone.shared.document.OrgObjectPrefixes;
import ug.co.smsone.testsupport.AbstractObjectStoreIntegrationTest;

/**
 * <b>ADR 0010 §6 hop 2→3's "SeaweedFS bucket root", proved against a real store.</b> The sibling of
 * {@code shared.deployment.DeploymentNamespaceTest}, which pins the NAMES; this pins what the names buy
 * when two deployments point at one endpoint.
 *
 * <p><b>The keys are identical on purpose, and that is the whole reason this test exists.</b>
 * {@code organization.id} does not change on extraction (ADR 0010 §2.2) and
 * {@code document.storage_key} travels verbatim in the tenant's dump, so after a cutover both
 * deployments legitimately hold the same key — which is what makes the restore a copy instead of a
 * column rewrite, and what keeps {@code OrgObjectPrefixes.covers} true on the far side. So the test
 * writes the SAME key from both deployments, deliberately: nothing inside a key can separate them, and
 * if the bucket does not, nothing does.
 */
class DeploymentBucketIntegrationTest extends AbstractObjectStoreIntegrationTest {

    @Autowired
    private S3Client s3;

    @Autowired
    private S3Presigner presigner;

    @Autowired
    private StorageProperties properties;

    @Autowired
    private DeploymentBucket platformBucket;

    /**
     * The bucket the running deployment addresses is exactly the configured one, because the shipped
     * {@code app.deployment.id} is the reserved {@code platform}. This is the byte-for-byte guarantee
     * from the far end: an existing installation upgrading into this change keeps addressing every
     * object it already wrote, rather than orphaning the lot in a bucket nothing names any more.
     */
    @Test
    void thePlatformKeepsAddressingTheBucketItAlreadyWroteInto() {
        assertThat(platformBucket.name()).isEqualTo(properties.bucket());
    }

    /**
     * The failure this prevents, run end to end: the source deployment purges a soft-deleted document
     * (which {@code SoftDeletePurgeJob} does on the platform long after a cutover, from rows the tenant
     * left behind) and the extracted deployment is still serving that exact key. Sharing a bucket, the
     * delete takes the far side's bytes and neither side errors — the platform deleted a key it had a
     * row for, and the far side's download simply 404s.
     */
    @Test
    void twoDeploymentsHoldingTheSameObjectKeyDoNotOverwriteOrDeleteEachOthersBytes() {
        UUID orgId = UUID.randomUUID();
        // The real shape, from the single declaration every minter and the extraction read.
        String key = OrgObjectPrefixes.forOrg(orgId).getFirst() + UUID.randomUUID() + "/report.pdf";

        var platform = providerFor(DeploymentIdentity.PLATFORM);
        var extracted = providerFor("acme");

        put(platform, key, "the platform's copy");
        put(extracted, key, "the extracted deployment's copy");

        assertThat(read(platform, key))
                .as("the second deployment's write must not land on the first's object")
                .isEqualTo("the platform's copy");
        assertThat(read(extracted, key)).isEqualTo("the extracted deployment's copy");

        platform.delete(key);

        assertThat(platform.exists(key)).isFalse();
        assertThat(extracted.exists(key))
                .as("the source's soft-delete purge must not remove bytes the extracted deployment is"
                        + " serving — after a cutover both sides hold this key legitimately")
                .isTrue();
    }

    /**
     * A deployment configured from a copy of the platform's environment — {@code S3_BUCKET} included —
     * still lands somewhere else. Sharing a bucket has to require sharing {@code app.deployment.id},
     * because that is one visible mistake instead of a silent one.
     */
    @Test
    void aDeploymentStartedFromTheSourcesConfigStillGetsItsOwnBucket() {
        DeploymentBucket copied = new DeploymentBucket(properties, new DeploymentIdentity("acme"));

        assertThat(copied.name())
                .isNotEqualTo(properties.bucket())
                .isEqualTo(properties.bucket() + "-dep-acme");
    }

    /** A provider wired exactly as the container wires it, but for a named deployment. */
    private S3StorageProvider providerFor(String deploymentId) {
        DeploymentBucket bucket = new DeploymentBucket(properties, new DeploymentIdentity(deploymentId));
        // BucketBootstrap creates only the running deployment's bucket; the other deployment's is its own
        // boot's job, so the test does here what that boot would do there.
        ensureBucket(bucket.name());
        return new S3StorageProvider(s3, presigner, properties, bucket);
    }

    private void ensureBucket(String name) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(name).build());
        } catch (NoSuchBucketException absent) {
            s3.createBucket(CreateBucketRequest.builder().bucket(name).build());
        }
    }

    private static void put(S3StorageProvider provider, String key, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        provider.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
    }

    private static String read(S3StorageProvider provider, String key) {
        try (var stream = provider.get(key)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + key, failure);
        }
    }
}

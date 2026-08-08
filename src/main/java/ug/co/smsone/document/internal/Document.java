package ug.co.smsone.document.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.document.NewDocument;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * The record of a stored file. Immutable after registration except through deletion.
 *
 * <p><b>Split table (ADR 0010 §2 row 6), and its null means something no other split table's does:
 * a null {@code org_id} is a PERSONAL document, not an unknown tenant.</b> It belongs to the human in
 * {@code owner_person_id} and it must NOT travel when one of their organizations is extracted — which
 * is exactly why it lives in {@code platform} rather than in any tenant's schema. An org document is
 * the tenant's and lives with the tenant.
 *
 * <p>Nothing here names a schema, and nothing needs to: the two scopes are reached through two
 * controllers whose axes already agree with the rule. {@code /api/v1/me/documents} names no
 * organization, so the request stays on the platform axis and resolves {@code platform.document};
 * {@code /api/v1/orgs/{orgId}/documents} pins that tenant and resolves theirs. There is no read that
 * spans both — a personal document is never listed beside an org's, by design — so the routing is the
 * {@code search_path}, which is the form that keeps working when a tenant is promoted to its own
 * schema. The predicates in {@code DocumentService} ({@code orgId is null and ownerPersonId = ?}) are
 * still load-bearing: they are what stops one person's personal documents being served to another.
 */
@Entity
@Table(name = "document")
@SQLDelete(sql = "update document set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Document extends SoftDeletableEntity {

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "owner_person_id", nullable = false, updatable = false)
    private UUID ownerPersonId;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 300)
    private String storageKey;

    @Column(nullable = false, updatable = false, length = 255)
    private String name;

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false, length = 20)
    private String source;

    protected Document() {
        // JPA
    }

    // No registerEvent here: the id is Hibernate-assigned at persist, so a creation event built in
    // the factory would carry null — DocumentService publishes DocumentRegistered explicitly after
    // save, the same exception the house makes for deletes.
    static Document register(NewDocument meta) {
        Document document = new Document();
        document.orgId = meta.orgId();
        document.ownerPersonId = meta.ownerPersonId();
        document.storageKey = meta.storageKey();
        document.name = meta.name();
        document.contentType = meta.contentType();
        document.sizeBytes = meta.sizeBytes();
        document.source = meta.source();
        return document;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getOwnerPersonId() {
        return ownerPersonId;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getName() {
        return name;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSource() {
        return source;
    }
}

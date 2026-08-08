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
 * <p>Nothing here names a schema, and nothing needs to: the routing is the {@code search_path}, which
 * is the form that keeps working when a tenant is promoted to its own schema.
 * {@code /api/v1/orgs/{orgId}/documents} pins that tenant at the edge and resolves theirs;
 * {@code /api/v1/documents} pins PLATFORM in every handler and resolves {@code platform.document}.
 *
 * <p><b>That second pin is explicit and must stay explicit.</b> An earlier version of this note said
 * the personal surface "names no organization, so the request stays on the platform axis" — which is
 * false twice over: the route is {@code /api/v1/documents}, and {@code CurrentUserFilter} pins the
 * caller's org whenever their token names exactly one, whatever the route. A single-org member's
 * personal upload therefore landed in that tenant's schema with a null {@code org_id}, where an
 * extraction would have carried it out with the organization. {@code PersonalDocumentController}'s
 * javadoc has the full failure list; do not remove the pins on the argument that the path has no
 * {@code orgId} in it.
 *
 * <p>There is no read that spans both homes — a personal document is never listed beside an org's, by
 * design. The predicates in {@code DocumentService} ({@code orgId is null and ownerPersonId = ?}) are
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

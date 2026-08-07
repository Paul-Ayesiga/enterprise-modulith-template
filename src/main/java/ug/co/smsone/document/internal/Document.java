package ug.co.smsone.document.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.document.NewDocument;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** The record of a stored file. Immutable after registration except through deletion. */
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

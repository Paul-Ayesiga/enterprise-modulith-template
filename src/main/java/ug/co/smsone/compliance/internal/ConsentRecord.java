package ug.co.smsone.compliance.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One consent decision. APPEND-ONLY: a withdrawal is a new row, so the history is intact.
 *
 * <p>{@code personId} is a soft ref to {@code person.id} with no FK — compliance is not the identity
 * module (V34). It used to hold a Keycloak subject, which is the worst place in the schema for a
 * foreign identifier: a consent that fails to match the human it was given by is a legal problem
 * rather than a bug.
 */
@Entity
@Table(name = "consent_record")
class ConsentRecord {

    @Id
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(nullable = false, length = 60)
    private String purpose;

    @Column(nullable = false)
    private boolean granted;

    @Column(length = 60)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ConsentRecord() {
        // JPA
    }

    static ConsentRecord of(UUID personId, String purpose, boolean granted, String source, Instant when) {
        ConsentRecord record = new ConsentRecord();
        record.id = UUID.randomUUID();
        record.personId = personId;
        record.purpose = purpose;
        record.granted = granted;
        record.source = source;
        record.createdAt = when;
        return record;
    }

    String getPurpose() {
        return purpose;
    }

    boolean isGranted() {
        return granted;
    }

    String getSource() {
        return source;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

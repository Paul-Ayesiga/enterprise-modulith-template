package ug.co.smsone.compliance.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * An active-until-released legal hold. Never soft-deleted; a released row is the trail.
 *
 * <p>Every id here is one of ours — {@code person.id} or {@code organization.id} — as a soft ref with
 * no FK (V34). {@code placedByPersonId} carries the consequence V34 states out loud: it is NOT NULL and
 * a machine has no person, so an API key cannot place a hold at all. The admin surface refuses one
 * rather than reaching for a null it cannot write.
 */
@Entity
@Table(name = "legal_hold")
class LegalHold {

    /**
     * The stored discriminator, and its vocabulary is deliberately UNCHANGED (V34): {@code SUBJECT}
     * means "{@code personId} is the one that is set". The factory below is named for the id it takes
     * rather than for this word — renaming the stored value is a data migration, and a Java constant
     * that disagreed with every row already written would be the worse of the two mismatches.
     */
    private static final String SCOPE_PERSON = "SUBJECT";
    private static final String SCOPE_ORG = "ORG";

    /**
     * Generated at {@code persist()}, never in the factory: with no {@code @Version} on this entity a
     * pre-assigned id makes Spring Data read {@code save()} as an update and route it through
     * {@code merge()} — a SELECT before every INSERT. The trap is spelled out in full on
     * {@link ConsentRecord}.
     */
    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 10)
    private String scope; // SUBJECT | ORG

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "placed_by_person_id", nullable = false)
    private UUID placedByPersonId;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by_person_id")
    private UUID releasedByPersonId;

    protected LegalHold() {
        // JPA
    }

    static LegalHold onPerson(UUID personId, String reason, UUID placedByPersonId, Instant when) {
        LegalHold hold = new LegalHold();
        hold.scope = SCOPE_PERSON;
        hold.personId = personId;
        hold.reason = reason;
        hold.placedByPersonId = placedByPersonId;
        hold.placedAt = when;
        return hold;
    }

    static LegalHold onOrg(UUID orgId, String reason, UUID placedByPersonId, Instant when) {
        LegalHold hold = new LegalHold();
        hold.scope = SCOPE_ORG;
        hold.orgId = orgId;
        hold.reason = reason;
        hold.placedByPersonId = placedByPersonId;
        hold.placedAt = when;
        return hold;
    }

    void release(UUID byPersonId, Instant when) {
        this.releasedAt = when;
        this.releasedByPersonId = byPersonId;
    }

    boolean isActive() {
        return releasedAt == null;
    }

    UUID getId() {
        return id;
    }

    String getScope() {
        return scope;
    }

    UUID getPersonId() {
        return personId;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getReason() {
        return reason;
    }

    Instant getPlacedAt() {
        return placedAt;
    }
}

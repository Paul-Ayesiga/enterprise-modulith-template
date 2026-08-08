package ug.co.smsone.signup.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One signup handshake. The token itself never touches this row — only its SHA-256. */
@Entity
@Table(name = "signup_request", schema = "platform")
class SignupRequest {

    static final String PENDING = "PENDING";
    static final String COMPLETED = "COMPLETED";

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "org_name", nullable = false, length = 80)
    private String orgName;

    // given/family, not first/last: the row this becomes is a person (V10) and the two must not
    // disagree about what a name is. Both nullable — a signup supplying only an email is valid.
    @Column(name = "given_name", length = 60)
    private String givenName;

    @Column(name = "family_name", length = 60)
    private String familyName;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "org_id")
    private UUID orgId;

    // person.id of the human this request produced — soft ref, no FK (identity is another module).
    // Set at COMPLETION with orgId and never at request time: minting an identity from an address
    // nobody has verified is the thing this handshake exists to prevent.
    @Column(name = "owner_person_id")
    private UUID ownerPersonId;

    protected SignupRequest() {
        // JPA
    }

    static SignupRequest pending(String email, String orgName, String givenName, String familyName,
            String tokenHash, Instant expiresAt, Instant now) {
        SignupRequest request = new SignupRequest();
        request.id = UUID.randomUUID();
        request.email = email;
        request.orgName = orgName;
        request.givenName = givenName;
        request.familyName = familyName;
        request.tokenHash = tokenHash;
        request.status = PENDING;
        request.expiresAt = expiresAt;
        request.createdAt = now;
        return request;
    }

    void completed(UUID orgId, UUID ownerPersonId, Instant now) {
        this.status = COMPLETED;
        this.orgId = orgId;
        this.ownerPersonId = ownerPersonId;
        this.completedAt = now;
    }

    boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    String getEmail() {
        return email;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getOrgName() {
        return orgName;
    }

    String getGivenName() {
        return givenName;
    }

    String getFamilyName() {
        return familyName;
    }

    String getStatus() {
        return status;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getOwnerPersonId() {
        return ownerPersonId;
    }
}

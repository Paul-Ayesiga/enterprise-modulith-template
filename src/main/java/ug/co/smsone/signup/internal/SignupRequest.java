package ug.co.smsone.signup.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One signup handshake. The token itself never touches this row — only its SHA-256. */
@Entity
@Table(name = "signup_request")
class SignupRequest {

    static final String PENDING = "PENDING";
    static final String COMPLETED = "COMPLETED";

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "org_name", nullable = false, length = 80)
    private String orgName;

    @Column(name = "first_name", length = 60)
    private String firstName;

    @Column(name = "last_name", length = 60)
    private String lastName;

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

    protected SignupRequest() {
        // JPA
    }

    static SignupRequest pending(String email, String orgName, String firstName, String lastName,
            String tokenHash, Instant expiresAt, Instant now) {
        SignupRequest request = new SignupRequest();
        request.id = UUID.randomUUID();
        request.email = email;
        request.orgName = orgName;
        request.firstName = firstName;
        request.lastName = lastName;
        request.tokenHash = tokenHash;
        request.status = PENDING;
        request.expiresAt = expiresAt;
        request.createdAt = now;
        return request;
    }

    void completed(UUID orgId, Instant now) {
        this.status = COMPLETED;
        this.orgId = orgId;
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

    String getFirstName() {
        return firstName;
    }

    String getLastName() {
        return lastName;
    }

    String getStatus() {
        return status;
    }

    UUID getOrgId() {
        return orgId;
    }
}

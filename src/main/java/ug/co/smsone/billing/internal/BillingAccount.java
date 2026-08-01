package ug.co.smsone.billing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** The org ↔ Kill Bill account linkage — a projection, so soft-deletable like `app_user`. */
@Entity
@Table(name = "billing_account")
@SQLDelete(sql = "update billing_account set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class BillingAccount extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "kb_account_id", nullable = false, updatable = false)
    private UUID kbAccountId;

    protected BillingAccount() {
        // JPA
    }

    static BillingAccount link(UUID orgId, UUID kbAccountId) {
        BillingAccount account = new BillingAccount();
        account.orgId = orgId;
        account.kbAccountId = kbAccountId;
        return account;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getKbAccountId() {
        return kbAccountId;
    }
}

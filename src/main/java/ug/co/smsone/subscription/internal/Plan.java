package ug.co.smsone.subscription.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * A plan and its entitlements — seeded vocabulary, not a user aggregate (hence {@code BaseEntity},
 * no soft delete). The entitlement map's encoding is the module's contract: key present with null
 * = feature on; key present with a value = numeric cap; key absent = feature off / unlimited.
 */
@Entity
@Table(name = "plan")
class Plan extends BaseEntity {

    @Column(nullable = false, length = 30, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "rank", nullable = false)
    private int rank;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_entitlement", joinColumns = @JoinColumn(name = "plan_id"))
    @MapKeyColumn(name = "ent_key")
    @Column(name = "limit_value")
    private Map<String, Long> entitlements = new LinkedHashMap<>();

    protected Plan() {
        // JPA
    }

    static Plan of(String code, String name, int rank, Map<String, Long> entitlements) {
        Plan plan = new Plan();
        plan.code = code;
        plan.name = name;
        plan.rank = rank;
        plan.entitlements = new LinkedHashMap<>(entitlements);
        return plan;
    }

    /** Update the mutable facets — code is the immutable identity. Mutates the collection in place so
     *  Hibernate keeps managing the {@code plan_entitlement} rows (replacing the instance would orphan them). */
    void update(String name, int rank, Map<String, Long> entitlements) {
        this.name = name;
        this.rank = rank;
        this.entitlements.clear();
        this.entitlements.putAll(entitlements);
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    int getRank() {
        return rank;
    }

    Map<String, Long> getEntitlements() {
        return entitlements;
    }
}

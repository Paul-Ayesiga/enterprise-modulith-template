package ug.co.smsone.integration.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** A provider configuration for one capability, at platform-default (orgId null) or org scope. */
@Entity
@Table(name = "integration")
@SQLDelete(sql = "update integration set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Integration extends SoftDeletableEntity {

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(nullable = false, updatable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false)
    private boolean enabled;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "integration_setting", joinColumns = @JoinColumn(name = "integration_id"))
    private List<Setting> settings = new ArrayList<>();

    @Embeddable
    record Setting(
            @Column(name = "setting_key", nullable = false, length = 60) String key,
            @Column(name = "setting_value", nullable = false, length = 600) String value,
            @Column(name = "is_secret", nullable = false) boolean secret) {
    }

    protected Integration() {
        // JPA
    }

    static Integration create(UUID orgId, String kind, String provider) {
        Integration integration = new Integration();
        integration.orgId = orgId;
        integration.kind = kind;
        integration.provider = provider;
        integration.enabled = true;
        return integration;
    }

    void update(String provider, boolean enabled, List<Setting> settings) {
        this.provider = provider;
        this.enabled = enabled;
        this.settings.clear();
        this.settings.addAll(settings);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getKind() {
        return kind;
    }

    String getProvider() {
        return provider;
    }

    boolean isEnabled() {
        return enabled;
    }

    List<Setting> getSettings() {
        return settings;
    }
}

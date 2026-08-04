package ug.co.smsone.geo.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ug.co.smsone.shared.geo.CaptureMode;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * The per-org, per-record-type capture policy — the "configure which forms need geo" switch. Not
 * soft-deletable: a policy is configuration, not a record with a retention story; turning capture off
 * is {@link CaptureMode#OFF}, and removing a policy is a hard delete.
 */
@Entity
@Table(name = "geo_capture_policy")
class GeoCapturePolicy extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "subject_type", nullable = false, length = 64, updatable = false)
    private String subjectType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CaptureMode mode;

    @Column(name = "min_accuracy_m", precision = 10, scale = 2)
    private BigDecimal minAccuracyM;

    /** Comma-joined {@link GeoSource} codes; null means any source is allowed. */
    @Column(name = "allowed_sources", length = 64)
    private String allowedSources;

    @Column(name = "max_fix_age_seconds")
    private Integer maxFixAgeSeconds;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Column(name = "coarsen_after_days")
    private Integer coarsenAfterDays;

    protected GeoCapturePolicy() {
        // JPA
    }

    static GeoCapturePolicy create(UUID orgId, String subjectType) {
        GeoCapturePolicy policy = new GeoCapturePolicy();
        policy.orgId = orgId;
        policy.subjectType = subjectType;
        policy.mode = CaptureMode.OFF;
        return policy;
    }

    void apply(CaptureMode mode, BigDecimal minAccuracyM, Set<GeoSource> allowedSources,
            Integer maxFixAgeSeconds, Integer retentionDays, Integer coarsenAfterDays) {
        this.mode = mode;
        this.minAccuracyM = minAccuracyM;
        this.allowedSources = (allowedSources == null || allowedSources.isEmpty()) ? null
                : allowedSources.stream().map(Enum::name).collect(Collectors.joining(","));
        this.maxFixAgeSeconds = maxFixAgeSeconds;
        this.retentionDays = retentionDays;
        this.coarsenAfterDays = coarsenAfterDays;
    }

    boolean allows(GeoSource source) {
        return allowedSourceSet().isEmpty() || allowedSourceSet().contains(source);
    }

    Set<GeoSource> allowedSourceSet() {
        if (allowedSources == null || allowedSources.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedSources.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(GeoSource::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    UUID getOrgId() {
        return orgId;
    }

    String getSubjectType() {
        return subjectType;
    }

    CaptureMode getMode() {
        return mode;
    }

    BigDecimal getMinAccuracyM() {
        return minAccuracyM;
    }

    Integer getMaxFixAgeSeconds() {
        return maxFixAgeSeconds;
    }

    Integer getRetentionDays() {
        return retentionDays;
    }

    Integer getCoarsenAfterDays() {
        return coarsenAfterDays;
    }
}

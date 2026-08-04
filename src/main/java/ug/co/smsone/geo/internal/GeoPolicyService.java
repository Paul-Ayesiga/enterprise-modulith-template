package ug.co.smsone.geo.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.geo.GeoPolicyChanged;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.geo.CaptureMode;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.web.ApiSource;

/** Reads and upserts the per-org, per-record-type capture policy, auditing and eventing each change. */
@Service
class GeoPolicyService {

    private final GeoCapturePolicyRepository policies;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    GeoPolicyService(GeoCapturePolicyRepository policies, AuditLog auditLog,
            ApplicationEventPublisher events, Clock clock) {
        this.policies = policies;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Optional<GeoCapturePolicy> find(UUID orgId, String subjectType) {
        return policies.findByOrgIdAndSubjectType(orgId, requireSubjectType(subjectType));
    }

    @Transactional
    GeoCapturePolicy set(UUID orgId, String subjectType, CaptureMode mode, BigDecimal minAccuracyM,
            Set<GeoSource> allowedSources, Integer maxFixAgeSeconds, Integer retentionDays, Integer coarsenAfterDays) {
        String type = requireSubjectType(subjectType);
        if (mode == null) {
            throw new ValidationException("mode is required.", ApiSource.pointer("/data/attributes/mode"));
        }
        GeoCapturePolicy policy = policies.findByOrgIdAndSubjectType(orgId, type)
                .orElseGet(() -> GeoCapturePolicy.create(orgId, type));
        CaptureMode previous = policy.getMode();
        policy.apply(mode, minAccuracyM, allowedSources, maxFixAgeSeconds, retentionDays, coarsenAfterDays);
        GeoCapturePolicy saved = policies.save(policy);
        auditLog.record("geo.policy_changed", orgId, type, "mode=" + previous, "mode=" + mode);
        events.publishEvent(new GeoPolicyChanged(orgId, type, mode, clock.instant()));
        return saved;
    }

    private static String requireSubjectType(String subjectType) {
        if (subjectType == null || subjectType.isBlank() || subjectType.length() > 64) {
            throw new ValidationException("subjectType is required (max 64 characters).",
                    ApiSource.parameter("subjectType"));
        }
        return subjectType.trim();
    }
}

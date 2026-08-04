package ug.co.smsone.geo.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.geo.GeoStampRecorded;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.geo.CaptureMode;
import ug.co.smsone.shared.geo.GeoFix;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.geo.GeoStamps;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ApiSource;

/**
 * The {@link GeoStamps} port impl. Attach validates the fix against the {@code (org, subjectType)}
 * capture policy, fills <em>who captured it</em> from the security context (like the audit log), writes
 * the row in the caller's transaction, audits it, and publishes {@link GeoStampRecorded} for
 * asynchronous reverse geocoding. A policy of {@code OFF} (or no policy) rejects an attach — capture is
 * opt-in per record type.
 */
@Component
class GeoStampsImpl implements GeoStamps {

    private final GeoStampRepository stamps;
    private final GeoCapturePolicyRepository policies;
    private final CurrentUserProvider currentUser;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    GeoStampsImpl(GeoStampRepository stamps, GeoCapturePolicyRepository policies,
            CurrentUserProvider currentUser, AuditLog auditLog, ApplicationEventPublisher events, Clock clock) {
        this.stamps = stamps;
        this.policies = policies;
        this.currentUser = currentUser;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public GeoStamp attach(UUID orgId, String subjectType, String subjectId, GeoFix fix) {
        String type = requireSubjectType(subjectType);
        String id = requireSubjectId(subjectId);
        GeoCapturePolicy policy = policies.findByOrgIdAndSubjectType(orgId, type).orElse(null);
        if (policy == null || policy.getMode() == CaptureMode.OFF) {
            throw new ValidationException(
                    "Geolocation capture is not enabled for '" + type + "' in this organization.",
                    ApiSource.pointer("/data/attributes/subjectType"));
        }
        validate(fix, policy);
        String capturedBy = currentUser.currentSubject().orElse(null);
        GeoStampEntity saved = stamps.save(GeoStampEntity.create(orgId, type, id, fix, capturedBy));
        auditLog.record("geo.stamp_recorded", orgId, type + ":" + id, null,
                "lat=" + fix.latitude() + " lng=" + fix.longitude() + " src=" + fix.source());
        events.publishEvent(new GeoStampRecorded(orgId, saved.getId(), type, id, capturedBy, clock.instant()));
        return saved.toStamp();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GeoStamp> findFor(UUID orgId, String subjectType, String subjectId) {
        return stamps.findByOrgIdAndSubjectTypeAndSubjectIdOrderByCapturedAtDesc(
                        orgId, requireSubjectType(subjectType), requireSubjectId(subjectId))
                .stream().map(GeoStampEntity::toStamp).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CaptureMode modeFor(UUID orgId, String subjectType) {
        return policies.findByOrgIdAndSubjectType(orgId, requireSubjectType(subjectType))
                .map(GeoCapturePolicy::getMode).orElse(CaptureMode.OFF);
    }

    private void validate(GeoFix fix, GeoCapturePolicy policy) {
        if (fix == null) {
            throw new ValidationException("A location fix is required.", ApiSource.pointer("/data/attributes"));
        }
        if (!fix.hasValidCoordinates()) {
            throw new ValidationException(
                    "latitude must be within [-90, 90] and longitude within [-180, 180].",
                    ApiSource.pointer("/data/attributes/latitude"));
        }
        if (policy.getMinAccuracyM() != null) {
            double min = policy.getMinAccuracyM().doubleValue();
            if (fix.accuracyM() == null || fix.accuracyM() > min) {
                throw new ValidationException(
                        "This location is too coarse: accuracy "
                                + (fix.accuracyM() == null ? "unknown" : fix.accuracyM() + " m")
                                + " exceeds the required " + min + " m.",
                        ApiSource.pointer("/data/attributes/accuracyM"));
            }
        }
        if (policy.getMaxFixAgeSeconds() != null) {
            Duration age = Duration.between(fix.capturedAt(), clock.instant());
            if (age.isNegative() || age.getSeconds() > policy.getMaxFixAgeSeconds()) {
                throw new ValidationException(
                        "This location fix is stale (older than the allowed " + policy.getMaxFixAgeSeconds() + "s).",
                        ApiSource.pointer("/data/attributes/capturedAt"));
            }
        }
        if (!policy.allows(fix.source())) {
            throw new ValidationException(
                    "Source " + fix.source() + " is not accepted for this record type.",
                    ApiSource.pointer("/data/attributes/source"));
        }
    }

    private static String requireSubjectType(String subjectType) {
        if (subjectType == null || subjectType.isBlank() || subjectType.length() > 64) {
            throw new ValidationException("subjectType is required (max 64 characters).",
                    ApiSource.pointer("/data/attributes/subjectType"));
        }
        return subjectType.trim();
    }

    private static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank() || subjectId.length() > 64) {
            throw new ValidationException("subjectId is required (max 64 characters).",
                    ApiSource.pointer("/data/attributes/subjectId"));
        }
        return subjectId.trim();
    }
}

package ug.co.smsone.geo.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.geo.CaptureMode;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The per-record-type capture policy — "configure which forms need geo". Both operations require
 * {@code geo:policy:manage}. Reading a record-type with no policy returns a default {@code OFF} view
 * rather than a 404, so a UI can render the form for any type.
 */
@RestController
class GeoPolicyController {

    private static final String RESOURCE_TYPE = "geo-capture-policy";

    private final GeoPolicyService policies;

    GeoPolicyController(GeoPolicyService policies) {
        this.policies = policies;
    }

    record PolicyRequest(CaptureMode mode, BigDecimal minAccuracyM, List<GeoSource> allowedSources,
            Integer maxFixAgeSeconds, Integer retentionDays, Integer coarsenAfterDays) {
    }

    record PolicyAttributes(String subjectType, String mode, BigDecimal minAccuracyM, List<String> allowedSources,
            Integer maxFixAgeSeconds, Integer retentionDays, Integer coarsenAfterDays) {
    }

    @GetMapping("/api/v1/orgs/{orgId}/geo/policies/{subjectType}")
    @Operation(summary = "Read the capture policy for a record-type")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'geo:policy:manage')")
    ResourceObject get(@PathVariable UUID orgId, @PathVariable String subjectType) {
        return policies.find(orgId, subjectType)
                .map(GeoPolicyController::toResource)
                .orElseGet(() -> new ResourceObject(subjectType, RESOURCE_TYPE,
                        new PolicyAttributes(subjectType, CaptureMode.OFF.name(), null, null, null, null, null)));
    }

    @PutMapping("/api/v1/orgs/{orgId}/geo/policies/{subjectType}")
    @Operation(summary = "Set the capture policy for a record-type",
            description = "OFF ignores fixes; OPTIONAL allows them; REQUIRED makes a valid fix mandatory for the record.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'geo:policy:manage')")
    ResourceObject set(@PathVariable UUID orgId, @PathVariable String subjectType, @RequestBody PolicyRequest body) {
        Set<GeoSource> sources = body == null || body.allowedSources() == null
                ? null : new LinkedHashSet<>(body.allowedSources());
        GeoCapturePolicy saved = policies.set(orgId, subjectType,
                body == null ? null : body.mode(),
                body == null ? null : body.minAccuracyM(), sources,
                body == null ? null : body.maxFixAgeSeconds(),
                body == null ? null : body.retentionDays(),
                body == null ? null : body.coarsenAfterDays());
        return toResource(saved);
    }

    private static ResourceObject toResource(GeoCapturePolicy policy) {
        Set<GeoSource> allowed = policy.allowedSourceSet();
        return new ResourceObject(policy.getSubjectType(), RESOURCE_TYPE, new PolicyAttributes(
                policy.getSubjectType(), policy.getMode().name(), policy.getMinAccuracyM(),
                allowed.isEmpty() ? null : allowed.stream().map(Enum::name).toList(),
                policy.getMaxFixAgeSeconds(), policy.getRetentionDays(), policy.getCoarsenAfterDays()));
    }
}

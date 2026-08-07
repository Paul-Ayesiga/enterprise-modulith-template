package ug.co.smsone.geo.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.geo.GeoFix;
import ug.co.smsone.shared.geo.GeoSource;
import ug.co.smsone.shared.geo.GeoStamp;
import ug.co.smsone.shared.geo.PlaceLabel;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * A stored location stamp. Coordinates are {@link BigDecimal} {@code numeric(9,6)} in the DB but only
 * ever cross the boundary as {@code double} (see {@link #toStamp()}), so nothing outside this package
 * speaks a spatial type. Soft-deletable so retention and legal holds apply (a location is sensitive).
 */
@Entity
@Table(name = "geo_stamp")
@SQLDelete(sql = "update geo_stamp set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class GeoStampEntity extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "subject_type", nullable = false, length = 64, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, length = 64, updatable = false)
    private String subjectId;

    @Column(nullable = false, precision = 9, scale = 6, updatable = false)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6, updatable = false)
    private BigDecimal longitude;

    @Column(name = "accuracy_m", precision = 10, scale = 2, updatable = false)
    private BigDecimal accuracyM;

    @Column(name = "altitude_m", precision = 10, scale = 2, updatable = false)
    private BigDecimal altitudeM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private GeoSource source;

    // person.id — soft ref, no FK. Null twice over on purpose (V47): an unauthenticated capture, and
    // a MACHINE capture — an API key is not any person, and "a robot stamped this" is honestly
    // recorded as no person id rather than as a manufactured one.
    @Column(name = "captured_by_person_id", updatable = false)
    private UUID capturedByPersonId;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "consent_ref", length = 64, updatable = false)
    private String consentRef;

    // Resolved place — filled asynchronously by reverse geocoding (Phase 2); null until then.
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(length = 120)
    private String admin1;

    @Column(length = 160)
    private String locality;

    @Column(name = "formatted_address", columnDefinition = "text")
    private String formattedAddress;

    @Column(name = "place_id", length = 128)
    private String placeId;

    @Column(name = "geocoder_provider", length = 24)
    private String geocoderProvider;

    protected GeoStampEntity() {
        // JPA
    }

    static GeoStampEntity create(UUID orgId, String subjectType, String subjectId, GeoFix fix,
            UUID capturedByPersonId) {
        GeoStampEntity entity = new GeoStampEntity();
        entity.orgId = orgId;
        entity.subjectType = subjectType;
        entity.subjectId = subjectId;
        entity.latitude = BigDecimal.valueOf(fix.latitude());
        entity.longitude = BigDecimal.valueOf(fix.longitude());
        entity.accuracyM = fix.accuracyM() == null ? null : BigDecimal.valueOf(fix.accuracyM());
        entity.altitudeM = fix.altitudeM() == null ? null : BigDecimal.valueOf(fix.altitudeM());
        entity.source = fix.source();
        entity.capturedAt = fix.capturedAt();
        entity.consentRef = fix.consentRef();
        entity.capturedByPersonId = capturedByPersonId;
        return entity;
    }

    GeoStamp toStamp() {
        return new GeoStamp(getId(), orgId, subjectType, subjectId,
                latitude.doubleValue(), longitude.doubleValue(),
                accuracyM == null ? null : accuracyM.doubleValue(),
                altitudeM == null ? null : altitudeM.doubleValue(),
                source, capturedByPersonId, capturedAt, consentRef,
                new PlaceLabel(countryCode, admin1, locality, formattedAddress));
    }

    UUID getOrgId() {
        return orgId;
    }

    String getSubjectType() {
        return subjectType;
    }
}

package ug.co.smsone.shared.geo;

import java.util.List;
import java.util.UUID;

/**
 * Attaches a location to a record and reads it back — a shared port so any module can stamp its own
 * records without depending on the {@code geo} module (the {@code geo} module provides the impl). It is
 * the geolocation counterpart of {@code shared.audit.AuditLog}: call it at the point of change, inside
 * the changing transaction, and the impl fills in <em>who captured it</em> from the security context.
 *
 * <p>The subject is a polymorphic <em>soft reference</em> ({@code subjectType} + {@code subjectId}) —
 * never a foreign key — so geo couples to no other module's schema, and any record type can be stamped.
 */
public interface GeoStamps {

    /**
     * Validates {@code fix} against the {@code (orgId, subjectType)} capture policy and, if it passes,
     * stores it against the subject and returns the stored stamp. Enforcement is the policy's:
     * coordinate range, minimum accuracy, freshness, and allowed source. A policy of {@code OFF} rejects
     * an attempt to attach; the resolved place starts empty and is filled asynchronously.
     *
     * @throws ug.co.smsone.shared.error.ValidationException if the fix violates the policy
     */
    GeoStamp attach(UUID orgId, String subjectType, String subjectId, GeoFix fix);

    /** Every stamp on a subject, newest first (a record may be re-stamped, e.g. on each update). */
    List<GeoStamp> findFor(UUID orgId, String subjectType, String subjectId);

    /**
     * The capture mode configured for this record-type — so an owning module can decide, before it
     * commits its record, whether a location is {@link CaptureMode#REQUIRED} (and reject when it has
     * none) or merely {@link CaptureMode#OPTIONAL}. Defaults to {@link CaptureMode#OFF} when no policy
     * is set.
     */
    CaptureMode modeFor(UUID orgId, String subjectType);
}

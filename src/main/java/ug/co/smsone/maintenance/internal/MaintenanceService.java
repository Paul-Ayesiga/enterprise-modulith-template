package ug.co.smsone.maintenance.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/** Scheduling and cancelling maintenance windows, and the read a client uses to render a banner. */
@Service
class MaintenanceService {

    private final MaintenanceWindowRepository windows;
    private final AuditLog auditLog;
    private final Clock clock;

    MaintenanceService(MaintenanceWindowRepository windows, AuditLog auditLog, Clock clock) {
        this.windows = windows;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * <b>Writes to the home {@code orgId} names, and therefore has to be CALLED on that axis</b> (ADR
     * 0010 §2 row 25). {@code windows.save} is unqualified, so the row lands wherever the connection's
     * {@code search_path} points; a platform operator scheduling a window FOR an org is on the platform
     * axis, so {@link AdminMaintenanceController} pins with {@code TenantContext.runAs(orgId, …)} before
     * calling. The pin cannot live in here — this method is {@code @Transactional}, the connection is
     * already bound by the time the body runs, and {@code TenantContext.set} throws for exactly that
     * reason.
     *
     * <p>The audit row rides the same decision without any help: {@link ug.co.smsone.shared.audit.AuditLog}
     * routes on the {@code orgId} it is handed, and it is handed this one.
     */
    @Transactional
    MaintenanceWindow schedule(UUID orgId, Instant startsAt, Instant endsAt, String mode, String message) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new ValidationException("endsAt must be after startsAt.",
                    ApiSource.pointer("/data/attributes/endsAt"));
        }
        String normalizedMode = mode == null ? "" : mode.trim().toUpperCase();
        if (!List.of("ANNOUNCE", "RESTRICT").contains(normalizedMode)) {
            throw new ValidationException("mode must be ANNOUNCE or RESTRICT.",
                    ApiSource.pointer("/data/attributes/mode"));
        }
        if (message == null || message.isBlank() || message.length() > 300) {
            throw new ValidationException("message is required (max 300 characters).",
                    ApiSource.pointer("/data/attributes/message"));
        }
        MaintenanceWindow window = windows.save(
                MaintenanceWindow.create(orgId, startsAt, endsAt, normalizedMode, message.trim()));
        auditLog.record("maintenance.window_scheduled", orgId, window.getId().toString(), null,
                "mode=" + normalizedMode + " starts=" + startsAt + " ends=" + endsAt);
        return window;
    }

    @Transactional
    void cancel(UUID orgId, UUID id) {
        MaintenanceWindow window = (orgId == null
                ? windows.findByIdAndOrgIdIsNull(id)
                : windows.findByIdAndOrgId(id, orgId))
                .orElseThrow(() -> new NotFoundException("Maintenance window not found."));
        windows.delete(window);
        auditLog.record("maintenance.window_cancelled", orgId, id.toString(), "mode=" + window.getMode(), null);
    }

    /**
     * Both arms are unqualified and both are correct, because each is only ever reached on the axis its
     * scope names: the platform list from {@code /api/v1/admin/maintenance} (platform axis, resolves
     * {@code platform.maintenance_window}) and the org list from {@code /api/v1/orgs/{orgId}/maintenance}
     * (that tenant's axis). {@code activeFor} is the one read that has to span both, and it is the one
     * that names a schema.
     */
    @Transactional(readOnly = true)
    List<MaintenanceWindow> list(UUID orgId) {
        return orgId == null
                ? windows.findByOrgIdIsNullOrderByStartsAtDesc()
                : windows.findByOrgIdOrderByStartsAtDesc(orgId);
    }

    /**
     * The windows currently in effect for an org (platform-wide + its own) — for the client banner and
     * for the two enforcement paths ({@link MaintenanceFilter}, {@link MaintenanceStateImpl}).
     *
     * <p><b>TWO READS, one per home (ADR 0010 §2 row 25).</b> The platform-wide half is named
     * explicitly — {@code platform.maintenance_window} — because this runs on the CALLER'S tenant axis
     * and an unqualified read would only ever see that tenant's own copy. The org half is unqualified,
     * so the {@code search_path} resolves it to whichever schema that tenant lives in. Both stay inside
     * one read-only transaction, so both see the same snapshot.
     *
     * <p>A null {@code orgId} means there is no tenant to add — a caller with no organization is still
     * subject to platform-wide windows and has none of its own.
     */
    @Transactional(readOnly = true)
    List<MaintenanceWindow> activeFor(UUID orgId) {
        Instant now = clock.instant();
        List<MaintenanceWindow> platformWide = windows.activePlatformWide(now);
        if (orgId == null) {
            return platformWide;
        }
        List<MaintenanceWindow> combined = new java.util.ArrayList<>(platformWide);
        combined.addAll(windows.activeForOrg(now, orgId));
        return combined;
    }
}

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

    @Transactional(readOnly = true)
    List<MaintenanceWindow> list(UUID orgId) {
        return orgId == null
                ? windows.findByOrgIdIsNullOrderByStartsAtDesc()
                : windows.findByOrgIdOrderByStartsAtDesc(orgId);
    }

    /** The windows currently in effect for an org (platform-wide + its own) — for the client banner. */
    @Transactional(readOnly = true)
    List<MaintenanceWindow> activeFor(UUID orgId) {
        return windows.activeFor(clock.instant(), orgId);
    }
}

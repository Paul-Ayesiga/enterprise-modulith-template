package ug.co.smsone.maintenance.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.maintenance.MaintenanceState;

/** The {@link MaintenanceState} port: the same query {@link MaintenanceFilter} runs for REST writes. */
@Component
class MaintenanceStateImpl implements MaintenanceState {

    private final MaintenanceService maintenance;

    MaintenanceStateImpl(MaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> writeBlockedUntil(UUID orgId) {
        // Same two-home read as the filter (ADR 0010 §2 row 25) — a platform-wide RESTRICT window has to
        // reach a caller running on a tenant's search_path, and only the service names `platform`.
        return maintenance.activeFor(orgId).stream()
                .filter(MaintenanceWindow::restricts)
                .findFirst()
                .map(MaintenanceWindow::getEndsAt);
    }
}

package ug.co.smsone.maintenance.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.maintenance.MaintenanceState;

/** The {@link MaintenanceState} port: the same query {@link MaintenanceFilter} runs for REST writes. */
@Component
class MaintenanceStateImpl implements MaintenanceState {

    private final MaintenanceWindowRepository windows;
    private final Clock clock;

    MaintenanceStateImpl(MaintenanceWindowRepository windows, Clock clock) {
        this.windows = windows;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> writeBlockedUntil(UUID orgId) {
        return windows.activeFor(clock.instant(), orgId).stream()
                .filter(MaintenanceWindow::restricts)
                .findFirst()
                .map(MaintenanceWindow::getEndsAt);
    }
}

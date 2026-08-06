package ug.co.smsone.mcp.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.maintenance.MaintenanceState;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.subscription.SubscriptionGate;

/**
 * Refuses mutations the {@code /api/**} write filters would refuse — a RESTRICT maintenance window
 * or a PAUSED subscription. Those filters key on URL shape and never see {@code /mcp}, so the same
 * two decisions are asked here through ports into the SAME modules; the surfaces cannot drift.
 * Maintenance is checked first: "come back at 04:30" beats "pay us" when both are true.
 */
@Component
class McpWriteGuard {

    private final MaintenanceState maintenance;
    private final SubscriptionGate subscriptions;

    McpWriteGuard(MaintenanceState maintenance, SubscriptionGate subscriptions) {
        this.maintenance = maintenance;
        this.subscriptions = subscriptions;
    }

    record Refusal(ErrorCode code, String detail, String outcome) {
    }

    Optional<Refusal> check(UUID orgId) {
        Optional<Instant> blockedUntil = maintenance.writeBlockedUntil(orgId);
        if (blockedUntil.isPresent()) {
            return Optional.of(new Refusal(ErrorCode.SERVICE_UNAVAILABLE,
                    "Undergoing maintenance until " + blockedUntil.get() + ". Writes are refused; retry after.",
                    "guard_maintenance"));
        }
        if (subscriptions.writesBlocked(orgId)) {
            return Optional.of(new Refusal(ErrorCode.SUBSCRIPTION_PAUSED,
                    "This organization's subscription is paused (its trial lapsed). It is read-only "
                            + "until a plan is assigned or a payment is made.",
                    "guard_subscription"));
        }
        return Optional.empty();
    }
}

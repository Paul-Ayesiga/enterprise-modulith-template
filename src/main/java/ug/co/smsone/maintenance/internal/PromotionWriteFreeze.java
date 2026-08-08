package ug.co.smsone.maintenance.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.tenancy.promotion.HttpWriteFreeze;

/**
 * {@link HttpWriteFreeze} over this module's own {@code maintenance_window} — the HTTP half of a tenant
 * promotion's freeze (ADR 0010 §6 hop 0→1).
 *
 * <p>It exists so the promoter, which lives in {@code shared}, can take a RESTRICT window without
 * {@code shared} compile-depending on this module (AGENTS §2.2) and without a second implementation of
 * a table this module owns. The window it opens is an ordinary one: {@code MaintenanceFilter} gates it
 * at {@code @Order(4)}, the client sees 503 with {@code Retry-After}, reads pass, and
 * {@code /maintenance} stays reachable so an operator can always cancel it by hand.
 *
 * <p>Two details are the promoter's requirements rather than this module's taste:
 *
 * <ul>
 *   <li><strong>The window starts in the past.</strong> {@code MaintenanceFilter} matches
 *       {@code starts_at <= now}, and a window stamped with exactly the instant it was created is one
 *       clock tick away from not yet being in effect — for a request that arrives in that tick, the
 *       freeze simply is not on. A second of backdating costs nothing and removes the race.</li>
 *   <li><strong>Cancelling something already gone is not an error.</strong> {@link #close} runs in the
 *       promoter's {@code finally}, on paths where the window may have been cancelled by an operator,
 *       or (on a demotion that failed after the flip) may be sitting in a schema the caller is no longer
 *       on. Turning that into a throw would replace a completed move's last step with a spurious
 *       failure.</li>
 * </ul>
 */
@Component
class PromotionWriteFreeze implements HttpWriteFreeze {

    private static final Logger log = LoggerFactory.getLogger(PromotionWriteFreeze.class);

    /** See the class note: {@code starts_at <= now} is an inclusive comparison against a moving clock. */
    private static final Duration ALREADY_IN_EFFECT = Duration.ofSeconds(1);

    private final MaintenanceService maintenance;
    private final Clock clock;

    PromotionWriteFreeze(MaintenanceService maintenance, Clock clock) {
        this.maintenance = maintenance;
        this.clock = clock;
    }

    @Override
    public UUID open(UUID orgId, Instant until, String message) {
        Instant from = clock.instant().minus(ALREADY_IN_EFFECT);
        if (!until.isAfter(from)) {
            throw new IllegalArgumentException("a promotion freeze that ends before it starts blocks"
                    + " nothing: asked for a window until " + until + " at " + from);
        }
        // schedule() writes an unqualified `maintenance_window`, so this lands in whichever schema the
        // calling axis names — which is the promoter's org, pinned before the call. That is deliberate:
        // the row is tenant-tier, travels with the copy, and so keeps gating without a gap across the
        // placement flip.
        MaintenanceWindow window = maintenance.schedule(orgId, from, until, "RESTRICT", message);
        log.info("organization {} is under a RESTRICT maintenance window until {} for a tenant move",
                orgId, until);
        return window.getId();
    }

    @Override
    public void close(UUID orgId, UUID windowId) {
        try {
            maintenance.cancel(orgId, windowId);
        } catch (NotFoundException alreadyGone) {
            log.info("the promotion freeze window {} for organization {} was already cancelled",
                    windowId, orgId);
        }
    }
}

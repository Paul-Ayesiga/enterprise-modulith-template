package ug.co.smsone.exchange.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * CRUD for recurring exports. Creation gates on the handler's EXPORT permission (the same
 * programmatic check a one-off submit passes), and the cron is validated here so a bad expression
 * is a 422 at create, never a firing-time surprise. Cron fields are Spring's six-field form,
 * evaluated in UTC — schedules must not drift with server timezones.
 */
@Service
class ExchangeScheduleService {

    private static final Sort SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ExchangeScheduleRepository schedules;
    private final ExchangeService exchange;
    private final AuditLog auditLog;
    private final Clock clock;
    private final ug.co.smsone.subscription.Entitlements entitlements;

    ExchangeScheduleService(ExchangeScheduleRepository schedules, ExchangeService exchange,
            AuditLog auditLog, Clock clock, ug.co.smsone.subscription.Entitlements entitlements) {
        this.schedules = schedules;
        this.exchange = exchange;
        this.auditLog = auditLog;
        this.clock = clock;
        this.entitlements = entitlements;
    }

    @Transactional
    ExchangeSchedule create(CurrentUser user, UUID orgId, String handlerId, String format, String cron) {
        ExchangeHandler handler = exchange.requireHandler(handlerId);
        String normalizedFormat = exchange.requireFormat(format);
        UUID requester = exchange.requireRequester(user);
        exchange.requirePermission(user, orgId, handler.exportPermission());
        entitlements.requireFeature(orgId, ug.co.smsone.subscription.EntitlementKeys.EXCHANGE_ENABLED);
        entitlements.requireWithinLimit(orgId,
                ug.co.smsone.subscription.EntitlementKeys.EXCHANGE_SCHEDULES_MAX,
                schedules.countByOrgId(orgId));
        if (cron == null || !CronExpression.isValidExpression(cron.trim())) {
            throw new ValidationException(
                    "cron must be a valid six-field Spring cron expression (e.g. '0 0 2 * * *').",
                    ApiSource.pointer("/data/attributes/cron"));
        }
        String trimmed = cron.trim();
        ExchangeSchedule schedule = schedules.save(ExchangeSchedule.create(
                orgId, requester, handler.id(), normalizedFormat, trimmed, next(trimmed, clock.instant())));
        auditLog.record("exchange.schedule_created", orgId, schedule.getId().toString(), null,
                "handler=" + handler.id() + " cron=" + trimmed);
        return schedule;
    }

    @Transactional(readOnly = true)
    Window<ExchangeSchedule> list(UUID orgId, CursorPageRequest page) {
        return schedules.findBy(
                (root, query, cb) -> cb.equal(root.get("orgId"), orgId),
                query -> query.limit(page.size()).sortBy(SORT).scroll(page.scrollPosition(SORT)));
    }

    @Transactional
    void delete(CurrentUser user, UUID orgId, UUID id) {
        ExchangeSchedule schedule = schedules.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Exchange schedule not found."));
        if (!schedule.getRequesterPersonId().equals(user.personId())) {
            ExchangeHandler handler = exchange.requireHandler(schedule.getHandler());
            exchange.requirePermission(user, orgId, handler.exportPermission());
        }
        schedules.delete(schedule);
        auditLog.record("exchange.schedule_deleted", orgId, id.toString(),
                "cron=" + schedule.getCron(), null);
    }

    /** UTC on purpose — the doc for the endpoint says so, and drift-free beats convenient. */
    static Instant next(String cron, Instant from) {
        ZonedDateTime nextRun = CronExpression.parse(cron)
                .next(ZonedDateTime.ofInstant(from, ZoneOffset.UTC));
        if (nextRun == null) {
            throw new ValidationException("cron never fires again.",
                    ApiSource.pointer("/data/attributes/cron"));
        }
        return nextRun.toInstant();
    }
}

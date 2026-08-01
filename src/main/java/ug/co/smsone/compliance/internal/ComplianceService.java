package ug.co.smsone.compliance.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.compliance.LegalHolds;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Consent history, legal holds, and GDPR erasure. Erasure is the sharp edge: a subject under an
 * active hold is REFUSED (the hold outranks the request); otherwise their owned rows are
 * soft-deleted now — invisible immediately — and the nightly purge hard-deletes them at the
 * retention window (which itself honors holds). The soft-delete is raw JDBC across the owned
 * tables, the same cross-cutting data-lifecycle reach the purge job has (AGENTS §7).
 */
@Service
class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);
    /** Subject-owned tables an erasure clears. Constants — never input. */
    private static final List<String> SUBJECT_TABLES = List.of("app_user", "user_profile", "user_device");

    private final ConsentRepository consents;
    private final LegalHoldRepository holds;
    private final ErasureRequestRepository erasures;
    private final LegalHolds legalHolds;
    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;
    private final Clock clock;

    ComplianceService(ConsentRepository consents, LegalHoldRepository holds,
            ErasureRequestRepository erasures, LegalHolds legalHolds, JdbcTemplate jdbc,
            AuditLog auditLog, Clock clock) {
        this.consents = consents;
        this.holds = holds;
        this.erasures = erasures;
        this.legalHolds = legalHolds;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    // ---- Consent (append-only) ----

    @Transactional
    ConsentRecord recordConsent(String subject, String purpose, boolean granted, String source) {
        if (purpose == null || purpose.isBlank() || purpose.length() > 60) {
            throw new ValidationException("purpose is required (max 60 characters).",
                    ApiSource.pointer("/data/attributes/purpose"));
        }
        ConsentRecord record = consents.save(
                ConsentRecord.of(subject, purpose.trim(), granted, source, clock.instant()));
        auditLog.record("compliance.consent_recorded", null, subject,
                null, "purpose=" + purpose.trim() + " granted=" + granted);
        return record;
    }

    @Transactional(readOnly = true)
    List<ConsentRecord> consentHistory(String subject) {
        return consents.findBySubjectOrderByCreatedAtDesc(subject);
    }

    // ---- Legal holds ----

    @Transactional
    LegalHold placeSubjectHold(String subject, String reason, String placedBy) {
        requireReason(reason);
        LegalHold hold = holds.save(LegalHold.onSubject(subject, reason.trim(), placedBy, clock.instant()));
        auditLog.record("compliance.legal_hold_placed", null, subject, null, "reason=" + reason.trim());
        return hold;
    }

    @Transactional
    LegalHold placeOrgHold(UUID orgId, String reason, String placedBy) {
        requireReason(reason);
        LegalHold hold = holds.save(LegalHold.onOrg(orgId, reason.trim(), placedBy, clock.instant()));
        auditLog.record("compliance.legal_hold_placed", orgId, orgId.toString(), null, "reason=" + reason.trim());
        return hold;
    }

    @Transactional(readOnly = true)
    List<LegalHold> activeHolds() {
        return holds.findByReleasedAtIsNullOrderByPlacedAtDesc();
    }

    @Transactional
    void releaseHold(UUID id, String releasedBy) {
        LegalHold hold = holds.findByIdAndReleasedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Active legal hold not found."));
        hold.release(releasedBy, clock.instant());
        holds.save(hold);
        auditLog.record("compliance.legal_hold_released", hold.getOrgId(),
                hold.getSubject() != null ? hold.getSubject() : String.valueOf(hold.getOrgId()), null, null);
    }

    // ---- Erasure ----

    @Transactional
    ErasureRequest requestErasure(String subject, String requestedBy) {
        ErasureRequest request = erasures.save(ErasureRequest.received(subject, requestedBy, clock.instant()));
        if (legalHolds.subjectHeld(subject)) {
            request.refused("A legal hold is in force for this subject; erasure is deferred until it is released.",
                    clock.instant());
            erasures.save(request);
            auditLog.record("compliance.erasure_refused", null, subject, null, "reason=legal-hold");
            return request;
        }
        int cleared = 0;
        for (String table : SUBJECT_TABLES) {
            cleared += jdbc.update("update " + table + " set deleted_at = now() "
                    + "where subject = ? and deleted_at is null", subject);
        }
        request.executed(clock.instant());
        erasures.save(request);
        auditLog.record("compliance.erasure_executed", null, subject, null, "rowsSoftDeleted=" + cleared);
        log.info("Erasure executed for subject {} — {} rows soft-deleted; purge hard-deletes at retention",
                subject, cleared);
        return request;
    }

    // ---- Privacy / portability ----

    @Transactional(readOnly = true)
    Map<String, Object> dataExport(String subject) {
        return Map.of(
                "subject", subject,
                "generatedAtHint", "server clock",
                "consents", consentHistory(subject).stream()
                        .map(c -> Map.of("purpose", c.getPurpose(), "granted", c.isGranted(),
                                "source", c.getSource() == null ? "" : c.getSource()))
                        .toList(),
                "underLegalHold", legalHolds.subjectHeld(subject));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw new ValidationException("reason is required (max 300 characters).",
                    ApiSource.pointer("/data/attributes/reason"));
        }
    }
}

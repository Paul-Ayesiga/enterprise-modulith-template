package ug.co.smsone.compliance.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.compliance.LegalHolds;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.security.PlatformAdmins;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Consent history, legal holds, and GDPR erasure. Erasure is the sharp edge: a person under an active
 * hold is REFUSED (the hold outranks the request); otherwise their owned rows are soft-deleted now —
 * invisible immediately — and the nightly purge hard-deletes them at the retention window (which itself
 * honors holds). The soft-delete is raw JDBC across the owned tables, the same cross-cutting
 * data-lifecycle reach the purge job has (AGENTS §7).
 *
 * <p>Every identity this service handles is a {@code person.id}. It used to be a Keycloak subject, and
 * V34 says why that was the worst place in the schema for a foreign identifier to live: an erasure that
 * matches the wrong rows — or none — is a legal problem rather than a bug.
 */
@Service
class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    /**
     * The active-holds keyset (AGENTS §3.3): stable AND unique. {@code placedAt} is the order an
     * operator reads holds in; {@code id} is the tiebreaker that makes the sort total. The tiebreaker
     * is not decoration — a litigation sweep places holds in a batch and {@code placed_at} is only as
     * fine as the clock, so ties are the normal case here, and a cursor built on a non-unique key
     * silently skips or repeats rows across the page seam.
     */
    private static final Sort HOLD_SORT = Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id"));

    /** A table an erasure clears, and the column it is keyed by. Constants — never input. */
    private record OwnedTable(String table, String keyColumn) {

        /**
         * The schema-qualified name the statement is actually built from, and it is not decoration.
         * Every table in {@link #ERASED_TABLES} is PLATFORM-tier (ADR 0010 §2 — the person graph is
         * one copy for the whole installation, because a human can belong to many tenants and
         * therefore to none), while erasure is reached from routes carrying every possible axis:
         * {@code /api/v1/me/erasure-request} on the caller's own tenant, {@code /admin/compliance}
         * with no tenant at all. An unqualified name resolves through whatever {@code search_path}
         * that borrow got, so on a tenant-pinned connection it would look inside {@code tenant_pool},
         * find nothing, and report five successful updates of zero rows — a legal obligation quietly
         * discharged against no data. Qualifying makes the statement mean the same thing from every
         * axis.
         */
        String qualified() {
            return "platform." + table;
        }
    }

    /**
     * The person-owned tables an erasure soft-deletes. Two shapes, hence the record rather than one
     * reused column name: {@code person} is keyed by its own {@code id}, everything else soft-references
     * it as {@code person_id}.
     *
     * <p>The two ADDITIONS over the old (account, profile, device) list are load-bearing, not tidying:
     *
     * <ul>
     *   <li>{@code external_identity} — the resolution key. A person soft-deleted while their link is
     *       still live is an account that reads as erased and still authenticates, because the edge
     *       resolves {@code (issuer, sub)} through that table and its unique index is partial on
     *       {@code deleted_at is null}.</li>
     *   <li>{@code person_contact} — {@code uq_person_contact_verified_live} is partial the same way, so
     *       a live contact row goes on holding the erased human's verified address hostage against
     *       every future signup at it.</li>
     * </ul>
     *
     * <p>Both of those DO have real FKs to {@code person} with {@code on delete cascade}, which is
     * exactly why leaving them out would look correct: the cascade fires on the HARD delete, thirty days
     * later, and erasure is meant to take effect now. {@code person_profile} and {@code user_device}
     * have no FK at all (V28/V31 — profile is a pre-drawn service seam), so nothing but this list ever
     * reaches them.
     *
     * <p>Bare names here, schema added by {@link OwnedTable#qualified()} where the statement is built —
     * see that method for why every one of them is platform-tier and what an unqualified erasure would
     * silently do.
     */
    private static final List<OwnedTable> ERASED_TABLES = List.of(
            new OwnedTable("person", "id"),
            new OwnedTable("external_identity", "person_id"),
            new OwnedTable("person_contact", "person_id"),
            new OwnedTable("person_profile", "person_id"),
            new OwnedTable("user_device", "person_id"));

    private final ConsentRepository consents;
    private final LegalHoldRepository holds;
    private final ErasureRequestRepository erasures;
    private final LegalHolds legalHolds;
    private final ObjectProvider<PlatformAdmins> platformAdmins;
    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;
    private final Clock clock;

    ComplianceService(ConsentRepository consents, LegalHoldRepository holds,
            ErasureRequestRepository erasures, LegalHolds legalHolds,
            ObjectProvider<PlatformAdmins> platformAdmins, JdbcTemplate jdbc,
            AuditLog auditLog, Clock clock) {
        this.consents = consents;
        this.holds = holds;
        this.erasures = erasures;
        this.legalHolds = legalHolds;
        this.platformAdmins = platformAdmins;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    // ---- Consent (append-only) ----

    @Transactional
    ConsentRecord recordConsent(UUID personId, String purpose, boolean granted, String source) {
        if (purpose == null || purpose.isBlank() || purpose.length() > 60) {
            throw new ValidationException("purpose is required (max 60 characters).",
                    ApiSource.pointer("/data/attributes/purpose"));
        }
        ConsentRecord record = consents.save(
                ConsentRecord.of(personId, purpose.trim(), granted, source, clock.instant()));
        auditLog.record("compliance.consent_recorded", null, personId.toString(),
                null, "purpose=" + purpose.trim() + " granted=" + granted);
        return record;
    }

    @Transactional(readOnly = true)
    List<ConsentRecord> consentHistory(UUID personId) {
        return consents.findByPersonIdOrderByCreatedAtDesc(personId);
    }

    // ---- Legal holds ----

    @Transactional
    LegalHold placePersonHold(UUID personId, String reason, UUID placedByPersonId) {
        requireReason(reason);
        LegalHold hold = holds.save(
                LegalHold.onPerson(personId, reason.trim(), placedByPersonId, clock.instant()));
        auditLog.record("compliance.legal_hold_placed", null, personId.toString(), null,
                "reason=" + reason.trim());
        return hold;
    }

    @Transactional
    LegalHold placeOrgHold(UUID orgId, String reason, UUID placedByPersonId) {
        requireReason(reason);
        LegalHold hold = holds.save(
                LegalHold.onOrg(orgId, reason.trim(), placedByPersonId, clock.instant()));
        auditLog.record("compliance.legal_hold_placed", orgId, orgId.toString(), null,
                "reason=" + reason.trim());
        return hold;
    }

    /**
     * Active holds, one keyset page at a time (ADR 0002). This used to hand back every un-released
     * hold as a single JSON list, which is unbounded by construction: holds are never deleted, only
     * released, and nothing caps how many a litigation sweep places at once. It also sorted every
     * active row on each call — the query has no index that satisfies {@code placed_at desc} under
     * {@code released_at is null}, so a 20-row page cost a top-N heapsort over all of them (measured:
     * 3,334 active holds → 95 buffers / 1.31 ms, versus 23 buffers / 0.10 ms once such an index
     * exists). That index is DDL and is reported to the migration owner rather than written here.
     */
    @Transactional(readOnly = true)
    Window<LegalHold> activeHolds(CursorPageRequest page) {
        return holds.findBy((root, query, cb) -> cb.isNull(root.get("releasedAt")),
                query -> query.limit(page.size()).sortBy(HOLD_SORT)
                        .scroll(page.scrollPosition(HOLD_SORT)));
    }

    @Transactional
    void releaseHold(UUID id, UUID releasedByPersonId) {
        LegalHold hold = holds.findByIdAndReleasedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Active legal hold not found."));
        hold.release(releasedByPersonId, clock.instant());
        holds.save(hold);
        // The target names whichever half of the hold was set — a person id or an org id — read through
        // `scope`, which is what audit_log.target being polymorphic text is for (V13).
        auditLog.record("compliance.legal_hold_released", hold.getOrgId(),
                String.valueOf(hold.getPersonId() != null ? hold.getPersonId() : hold.getOrgId()), null, null);
    }

    // ---- Erasure ----

    @Transactional
    ErasureRequest requestErasure(UUID personId, UUID requestedByPersonId) {
        // Saved BEFORE the decision, so a request that is about to be refused still exists as a record
        // of having been made. The save()s further down land on this same managed instance, so they
        // cost nothing extra — see ErasureRequest's id field for why only the first one ever could.
        ErasureRequest request = erasures.save(
                ErasureRequest.received(personId, requestedByPersonId, clock.instant()));
        if (legalHolds.personHeld(personId)) {
            request.refused("A legal hold is in force for this person; erasure is deferred until it is released.",
                    clock.instant());
            erasures.save(request);
            auditLog.record("compliance.erasure_refused", null, personId.toString(), null, "reason=legal-hold");
            return request;
        }
        // Never erase the platform's last super-admin — it would lock out role administration until the
        // bootstrap re-seeds one on the next restart. Grant the role elsewhere first, then erase.
        PlatformAdmins admins = platformAdmins.getIfAvailable();
        if (admins != null && admins.isSoleSuperAdmin(personId)) {
            request.refused("This account is the last platform super-admin; grant that role to another "
                    + "account before erasing this one.", clock.instant());
            erasures.save(request);
            auditLog.record("compliance.erasure_refused", null, personId.toString(), null, "reason=last-super-admin");
            return request;
        }
        int cleared = 0;
        for (OwnedTable owned : ERASED_TABLES) {
            cleared += jdbc.update("update " + owned.qualified() + " set deleted_at = now() "
                    + "where " + owned.keyColumn() + " = ? and deleted_at is null", personId);
        }
        // person_preference is the one thing erasure REMOVES outright, because it is the one thing that
        // cannot be soft-deleted: V28 gave it no deleted_at (it has no lifecycle beyond its person) and
        // no FK (profile is a pre-drawn service seam), so there is no state that means "erased" and no
        // cascade that will ever come for it. Not deleting it here means it survives the human forever.
        // The hold check above has already run, so the only cost is that restoring a person inside the
        // recovery window restores them without their preferences — the right trade against keeping data
        // somebody exercised a legal right to have destroyed.
        int preferences = jdbc.update("delete from platform.person_preference where person_id = ?", personId);
        request.executed(clock.instant());
        erasures.save(request);
        auditLog.record("compliance.erasure_executed", null, personId.toString(), null,
                "rowsSoftDeleted=" + cleared + " preferencesDeleted=" + preferences);
        log.info("Erasure executed for person {} — {} rows soft-deleted, {} preference rows deleted; "
                + "purge hard-deletes the rest at retention", personId, cleared, preferences);
        return request;
    }

    // ---- Privacy / portability ----

    @Transactional(readOnly = true)
    Map<String, Object> dataExport(UUID personId) {
        return Map.of(
                "personId", personId.toString(),
                "generatedAtHint", "server clock",
                "consents", consentHistory(personId).stream()
                        .map(c -> Map.of("purpose", c.getPurpose(), "granted", c.isGranted(),
                                "source", c.getSource() == null ? "" : c.getSource()))
                        .toList(),
                "underLegalHold", legalHolds.personHeld(personId));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw new ValidationException("reason is required (max 300 characters).",
                    ApiSource.pointer("/data/attributes/reason"));
        }
    }
}

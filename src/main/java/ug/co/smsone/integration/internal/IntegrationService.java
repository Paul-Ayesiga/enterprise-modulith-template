package ug.co.smsone.integration.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.integration.Integrations;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * Managing integrations at either scope. Secret setting values are encrypted on write and never
 * returned in the clear from the REST layer (the {@code Integrations} port decrypts for in-JVM
 * consumers). One enabled integration per (scope, kind) — a second create for the same pair is a
 * 409, so resolution stays deterministic.
 *
 * <p><b>Every read and write here is unqualified, and every one of them is reached on the axis its
 * scope names</b> (ADR 0010 §2 rows 22–23). The platform-default surface is
 * {@code /api/v1/admin/integrations}, whose caller has no organization and therefore runs on the
 * platform axis; the org surface is {@code /api/v1/orgs/{orgId}/integrations}, whose caller runs on
 * that tenant's. The {@code orgId == null} branches below are not schema selection — the
 * {@code search_path} has already done that — they select the {@code org_id is null} PREDICATE, which
 * still matters because a home could otherwise hand back a row belonging to the other scope. The one
 * caller that has to span both homes at once is {@link IntegrationsImpl}, and it names its schemas.
 */
@Service
class IntegrationService {

    /** Setting keys whose values are secrets — encrypted at rest, masked on read. */
    private static final java.util.Set<String> SECRET_KEYS =
            java.util.Set.of("apiKey", "apiSecret", "authToken", "password", "secretKey", "accessKey");

    private final IntegrationRepository integrations;
    private final IntegrationSecretCipher cipher;
    private final AuditLog auditLog;

    IntegrationService(IntegrationRepository integrations, IntegrationSecretCipher cipher, AuditLog auditLog) {
        this.integrations = integrations;
        this.cipher = cipher;
        this.auditLog = auditLog;
    }

    record View(UUID id, UUID orgId, String kind, String provider, boolean enabled,
            Map<String, String> settings) {
    }

    @Transactional
    View upsert(UUID orgId, String kindRaw, String provider, boolean enabled, Map<String, String> settings) {
        Integrations.Kind kind = parseKind(kindRaw);
        requireText(provider, "provider");
        Integration existing = (orgId == null
                ? integrations.findByOrgIdIsNullAndKind(kind.name())
                : integrations.findByOrgIdAndKind(orgId, kind.name())).orElse(null);
        Integration integration = existing != null ? existing : Integration.create(orgId, kind.name(), provider);
        integration.update(provider, enabled, encrypt(settings));
        Integration saved;
        try {
            saved = integrations.save(integration);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ConflictException("An integration for this scope and kind already exists.");
        }
        auditLog.record("integration.configured", orgId, saved.getId().toString(), null,
                "kind=" + kind + " provider=" + provider + " enabled=" + enabled);
        return toView(saved);
    }

    @Transactional(readOnly = true)
    List<View> list(UUID orgId) {
        List<Integration> rows = orgId == null
                ? integrations.findByOrgIdIsNullOrderByKindAsc()
                : integrations.findByOrgIdOrderByKindAsc(orgId);
        return rows.stream().map(this::toView).toList();
    }

    @Transactional
    void delete(UUID orgId, UUID id) {
        Integration integration = (orgId == null
                ? integrations.findByIdAndOrgIdIsNull(id)
                : integrations.findByIdAndOrgId(id, orgId))
                .orElseThrow(() -> new NotFoundException("Integration not found."));
        integrations.delete(integration);
        auditLog.record("integration.removed", orgId, id.toString(), "kind=" + integration.getKind(), null);
    }

    /**
     * A no-op reachability probe: resolve the effective config and confirm the required keys are
     * present. A real provider check would call out; this validates configuration completeness
     * without a network dependency, and the result names what is missing.
     */
    @Transactional(readOnly = true)
    String testConnection(UUID orgId, UUID id) {
        Integration integration = (orgId == null
                ? integrations.findByIdAndOrgIdIsNull(id)
                : integrations.findByIdAndOrgId(id, orgId))
                .orElseThrow(() -> new NotFoundException("Integration not found."));
        if (!integration.isEnabled()) {
            return "disabled";
        }
        return integration.getSettings().isEmpty() ? "no settings configured" : "ok";
    }

    private List<Integration.Setting> encrypt(Map<String, String> settings) {
        List<Integration.Setting> result = new ArrayList<>();
        if (settings == null) {
            return result;
        }
        settings.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            boolean secret = SECRET_KEYS.contains(key);
            result.add(new Integration.Setting(key.trim(),
                    secret ? cipher.encrypt(value) : value, secret));
        });
        return result;
    }

    private View toView(Integration integration) {
        Map<String, String> masked = new java.util.LinkedHashMap<>();
        for (Integration.Setting setting : integration.getSettings()) {
            masked.put(setting.key(), setting.secret() ? "••••••" : setting.value());
        }
        return new View(integration.getId(), integration.getOrgId(), integration.getKind(),
                integration.getProvider(), integration.isEnabled(), masked);
    }

    private static Integrations.Kind parseKind(String kind) {
        try {
            return Integrations.Kind.valueOf(kind == null ? "" : kind.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("kind must be one of SMS_PROVIDER, EMAIL_PROVIDER, PAYMENT_GATEWAY.",
                    ApiSource.pointer("/data/attributes/kind"));
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 40) {
            throw new ValidationException(field + " is required (max 40 characters).",
                    ApiSource.pointer("/data/attributes/" + field));
        }
    }
}

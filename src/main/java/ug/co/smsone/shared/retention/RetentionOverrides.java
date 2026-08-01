package ug.co.smsone.shared.retention;

import java.util.Map;
import java.util.UUID;

/**
 * Per-org retention overrides for the org-scoped logs (see {@link RetentionScope}). A retention job
 * consults this to keep (or drop) one org's rows on a different schedule than the platform default —
 * an enterprise data-retention contract, typically. Implemented by the scheduler module, which owns
 * the table; the kernel default (no owning module) is "no overrides", i.e. everyone on the default.
 */
public interface RetentionOverrides {

    /**
     * Retention in DAYS per org for {@code scope}. Orgs ABSENT from the map use the platform
     * default; a present value REPLACES it for that org (longer or shorter). Small — a handful of
     * contracted orgs — so a job may hold it for the length of one purge run.
     */
    Map<UUID, Integer> daysByScope(String scope);
}
